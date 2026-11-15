package vip.mseapp.open.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import vip.mseapp.core.schedule.MseTaskScheduler;
import vip.mseapp.core.schedule.TaskDefinition;
import vip.mseapp.open.api.OpenApiContributor;
import vip.mseapp.open.api.OpenApiDescriptor;
import vip.mseapp.open.api.RemoteOpenApiRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 开放 API 自注册推送器：应用就绪后把全部 {@link OpenApiContributor} 描述推送至开放平台，
 * 并按周期重推自愈（mse-open 未就绪/重启丢失后自动补注册）。
 *
 * <p>注册通道按 {@code mse.rpc.mode} 解析，与基座 RPC 双形态一致：
 * <ul>
 *   <li>local（单体）：容器内直调 {@link RemoteOpenApiRegistry} 唯一实现（聚合了 open-service
 *       即存在）；不存在说明宿主未组装开放平台，告警一次后停止重试；</li>
 *   <li>dubbo（微服务）：经 {@link OpenDubboRegistryReference} 远调 mse-open（本类保持零
 *       Dubbo import，与基座同款探测门控），check=false 启动不依赖对方就绪。</li>
 * </ul>
 * 调度经统一调度中心（taskId=open-api-registry-push：延迟启动 + 多实例防重），
 * Contributor 为空则整体闲置。
 */
public class OpenApiRegistrationPusher implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OpenApiRegistrationPusher.class);

    private final ObjectProvider<OpenApiContributor> contributors;
    private final Environment environment;
    private final org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory;
    private final long intervalMinutes;

    private final ObjectProvider<MseTaskScheduler> schedulerProvider;

    /** local 模式解析结果：宿主未组装开放平台时置 localAbsent 哨兵，停止重试 */
    private volatile RemoteOpenApiRegistry localRegistry;
    private volatile boolean localAbsent;
    private final AtomicBoolean localAbsentWarned = new AtomicBoolean(false);

    public OpenApiRegistrationPusher(ObjectProvider<OpenApiContributor> contributors,
                                     Environment environment,
                                     org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
                                     ObjectProvider<MseTaskScheduler> schedulerProvider) {
        this.contributors = contributors;
        this.environment = environment;
        this.beanFactory = beanFactory;
        this.schedulerProvider = schedulerProvider;
        this.intervalMinutes = Math.max(1,
                environment.getProperty("mse.open.registry.interval-minutes", Long.class, 5L));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<OpenApiContributor> found = contributors.orderedStream().toList();
        if (found.isEmpty()) {
            log.debug("[open-api] 无 OpenApiContributor，自注册推送器闲置");
            return;
        }
        MseTaskScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            log.info("[open-api] 调度器未装配（mse.scheduling.mode=none 等），开放 API 自注册推送不启动");
            return;
        }
        scheduler.register(TaskDefinition.fixedDelay("open-api-registry-push", "开放 API 自注册推送",
                        "推送 OpenApiContributor 描述至开放平台目录并周期重推自愈", 0,
                        TimeUnit.MINUTES.toMillis(intervalMinutes))
                        .category(TaskDefinition.CATEGORY_PLATFORM),
                this::pushSafely);
    }

    private void pushSafely() {
        try {
            push();
        } catch (Throwable e) {
            log.warn("[open-api] 开放 API 注册推送失败，将在下个周期重试: {}", e.getMessage());
        }
    }

    private void push() {
        List<OpenApiDescriptor> descriptors = new ArrayList<>();
        contributors.orderedStream().forEach(contributor -> descriptors.addAll(contributor.descriptors()));
        if (descriptors.isEmpty()) {
            return;
        }
        RemoteOpenApiRegistry registry = resolveRegistry();
        if (registry == null) {
            return; // 等待下个周期（dubbo 对端未就绪 / local 已告警停推）
        }
        registry.register(descriptors);
        log.info("[open-api] 已推送 {} 条开放 API 描述至开放平台目录", descriptors.size());
    }

    private RemoteOpenApiRegistry resolveRegistry() {
        if ("dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"))
                && dubboPresent()) {
            // 仅在 dubbo 分支触达 OpenDubboRegistryReference（宿主无 Dubbo 时该类不被加载）
            return OpenDubboRegistryReference.proxy(environment);
        }
        if (localRegistry == null && !localAbsent) {
            synchronized (this) {
                if (localRegistry == null && !localAbsent) {
                    var candidates = beanFactory.getBeansOfType(RemoteOpenApiRegistry.class);
                    if (candidates.isEmpty()) {
                        localAbsent = true;
                        if (localAbsentWarned.compareAndSet(false, true)) {
                            log.warn("[open-api] 容器内无 RemoteOpenApiRegistry 实现（宿主未聚合 open-service），跳过开放 API 自注册");
                        }
                    } else if (candidates.size() > 1) {
                        localAbsent = true;
                        log.warn("[open-api] 容器内存在多个 RemoteOpenApiRegistry 实现 {}，跳过开放 API 自注册", candidates.keySet());
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
            OpenDubboRegistryReference.destroy();
        }
    }
}
