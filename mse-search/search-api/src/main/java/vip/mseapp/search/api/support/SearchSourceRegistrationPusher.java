package vip.mseapp.search.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import vip.mseapp.core.schedule.MseTaskScheduler;
import vip.mseapp.core.schedule.TaskDefinition;
import vip.mseapp.search.api.RemoteSearchSourceService;
import vip.mseapp.search.api.SearchSourceContributor;
import vip.mseapp.search.api.SearchSourceDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 可搜索源自注册推送器：应用就绪后把全部 {@link SearchSourceContributor} 的源描述推送至
 * 搜索引擎目录（mse-search），并按周期重推自愈（mse-search 未就绪/重启丢失后自动补注册）。
 *
 * <p>注册通道按 {@code mse.rpc.mode} 解析，与基座 RPC 双形态一致：
 * <ul>
 *   <li>local（单体）：容器内直调 {@link RemoteSearchSourceService} 唯一实现（聚合了
 *       search-service 即存在）；不存在说明宿主未组装搜索引擎，告警一次后停止重试；</li>
 *   <li>dubbo（微服务）：经 {@link SearchDubboRegistryReference} 远调 mse-search（本类保持零
 *       Dubbo import，与基座同款探测门控），check=false 启动不依赖对方就绪。</li>
 * </ul>
 * 调度经统一调度中心（taskId=search-source-registry-push：延迟启动 + 多实例防重），
 * Contributor 为空则整体闲置。
 */
public class SearchSourceRegistrationPusher implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchSourceRegistrationPusher.class);

    private final ObjectProvider<SearchSourceContributor> contributors;
    private final Environment environment;
    private final org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory;
    private final long intervalMinutes;

    private final ObjectProvider<MseTaskScheduler> schedulerProvider;

    /** local 模式解析结果：宿主未组装搜索引擎时置 localAbsent 哨兵，停止重试 */
    private volatile RemoteSearchSourceService localRegistry;
    private volatile boolean localAbsent;
    private final AtomicBoolean localAbsentWarned = new AtomicBoolean(false);

    public SearchSourceRegistrationPusher(ObjectProvider<SearchSourceContributor> contributors,
                                          Environment environment,
                                          org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
                                          ObjectProvider<MseTaskScheduler> schedulerProvider) {
        this.contributors = contributors;
        this.environment = environment;
        this.beanFactory = beanFactory;
        this.schedulerProvider = schedulerProvider;
        this.intervalMinutes = Math.max(1,
                environment.getProperty("mse.search.registry.interval-minutes", Long.class, 5L));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<SearchSourceContributor> found = contributors.orderedStream().toList();
        if (found.isEmpty()) {
            log.debug("[mse-search] 无 SearchSourceContributor，自注册推送器闲置");
            return;
        }
        MseTaskScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            log.info("[mse-search] 调度器未装配（mse.scheduling.mode=none 等），可搜索源自注册推送不启动");
            return;
        }
        scheduler.register(TaskDefinition.fixedDelay("search-source-registry-push", "可搜索源自注册推送",
                        "推送 SearchSourceContributor 源描述至 mse-search 目录并周期重推自愈", 0,
                        TimeUnit.MINUTES.toMillis(intervalMinutes))
                        .category(TaskDefinition.CATEGORY_PLATFORM),
                this::pushSafely);
    }

    private void pushSafely() {
        try {
            push();
        } catch (Throwable e) {
            log.warn("[mse-search] 可搜索源注册推送失败，将在下个周期重试: {}", e.getMessage());
        }
    }

    private void push() {
        List<SearchSourceDescriptor> descriptors = new ArrayList<>();
        contributors.orderedStream().forEach(contributor -> {
            List<SearchSourceDescriptor> sources = contributor.sources();
            if (sources != null) {
                descriptors.addAll(sources);
            }
        });
        if (descriptors.isEmpty()) {
            return;
        }
        RemoteSearchSourceService registry = resolveRegistry();
        if (registry == null) {
            return; // 等待下个周期（dubbo 对端未就绪 / local 已告警停推）
        }
        registry.register(descriptors);
        log.info("[mse-search] 已推送 {} 条可搜索源描述至搜索引擎目录", descriptors.size());
    }

    private RemoteSearchSourceService resolveRegistry() {
        if ("dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"))
                && dubboPresent()) {
            // 仅在 dubbo 分支触达 SearchDubboRegistryReference（宿主无 Dubbo 时该类不被加载）
            return SearchDubboRegistryReference.proxy(environment);
        }
        if (localRegistry == null && !localAbsent) {
            synchronized (this) {
                if (localRegistry == null && !localAbsent) {
                    var candidates = beanFactory.getBeansOfType(RemoteSearchSourceService.class);
                    if (candidates.isEmpty()) {
                        localAbsent = true;
                        if (localAbsentWarned.compareAndSet(false, true)) {
                            log.warn("[mse-search] 容器内无 RemoteSearchSourceService 实现（宿主未聚合 search-service），跳过可搜索源自注册");
                        }
                    } else if (candidates.size() > 1) {
                        localAbsent = true;
                        log.warn("[mse-search] 容器内存在多个 RemoteSearchSourceService 实现 {}，跳过可搜索源自注册", candidates.keySet());
                    } else {
                        localRegistry = candidates.values().iterator().next();
                    }
                }
            }
        }
        return localRegistry;
    }

    private boolean dubboPresent() {
        try {
            Class.forName("org.apache.dubbo.config.ReferenceConfig");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void destroy() {
        if ("dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"))
                && dubboPresent()) {
            SearchDubboRegistryReference.destroy();
        }
    }
}
