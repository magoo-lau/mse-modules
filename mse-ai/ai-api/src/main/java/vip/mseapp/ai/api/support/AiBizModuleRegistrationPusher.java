package vip.mseapp.ai.api.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import vip.mseapp.ai.api.AiBizModuleProvider;
import vip.mseapp.ai.api.RemoteAiBizModuleRegistry;
import vip.mseapp.ai.api.dto.AiBizModuleDescriptor;
import vip.mseapp.core.schedule.MseTaskScheduler;
import vip.mseapp.core.schedule.TaskDefinition;

/**
 * 业务模块自注册推送器：应用就绪后把容器内全部 {@link AiBizModuleProvider} 的声明推送至
 * mse-ai 模块注册中心，并按周期重推自愈（mse-ai 未就绪/重启丢失后自动补登记）。
 *
 * <p>通道自检：宿主容器内已存在 {@link RemoteAiBizModuleRegistry} 实现（单体聚合 ai-service 或
 * mse-ai 自身）时整体闲置——LocalBizModuleAutoRegistrar 已直接收集本地 AiBizModuleProvider
 * Bean，推送只会制造重复处理；纯业务微服务（dubbo 模式）经
 * {@link AiDubboBizModuleRegistryReference} 远推 mse-ai（check=false 启动不依赖对方就绪）；
 * local 模式且无实现说明模块无登记通道，告警一次后闲置。
 *
 * <p>调度经统一调度中心（taskId=ai-biz-module-registry-push：延迟启动 + 多实例防重）；容器内无
 * AiBizModuleProvider Bean 则整体闲置。本类保持零 Dubbo import（Dubbo 引用收在
 * {@link AiDubboBizModuleRegistryReference}）。
 */
public class AiBizModuleRegistrationPusher implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AiBizModuleRegistrationPusher.class);

    private final ObjectProvider<AiBizModuleProvider> providers;
    private final Environment environment;
    private final org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory;
    private final long intervalMinutes;

    private final ObjectProvider<MseTaskScheduler> schedulerProvider;

    /** local 模式且宿主未聚合 mse-ai：模块无登记通道，告警一次后停推 */
    private final AtomicBoolean unconsumableWarned = new AtomicBoolean(false);

    public AiBizModuleRegistrationPusher(ObjectProvider<AiBizModuleProvider> providers,
                                         Environment environment,
                                         org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
                                         ObjectProvider<MseTaskScheduler> schedulerProvider) {
        this.providers = providers;
        this.environment = environment;
        this.beanFactory = beanFactory;
        this.schedulerProvider = schedulerProvider;
        this.intervalMinutes = Math.max(1,
                environment.getProperty("mse.ai.biz-module-registry.interval-minutes", Long.class, 5L));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (providers.stream().findAny().isEmpty()) {
            log.debug("[ai-api] 无 AiBizModuleProvider Bean，业务模块自注册推送器闲置");
            return;
        }
        if (!beanFactory.getBeansOfType(RemoteAiBizModuleRegistry.class).isEmpty()) {
            log.info("[ai-api] 宿主容器内已有 RemoteAiBizModuleRegistry 实现（本地收集已覆盖），模块推送器闲置");
            return;
        }
        if (!"dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local")) || !dubboPresent()) {
            if (unconsumableWarned.compareAndSet(false, true)) {
                log.warn("[ai-api] local 模式且未聚合 mse-ai，AiBizModuleProvider 声明无登记通道，跳过自注册推送");
            }
            return;
        }
        MseTaskScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            log.info("[ai-api] 调度器未装配（mse.scheduling.mode=none 等），业务模块自注册推送不启动");
            return;
        }
        scheduler.register(TaskDefinition.fixedDelay("ai-biz-module-registry-push", "AI 业务模块自注册推送",
                        "推送容器内 AiBizModuleProvider 声明至 mse-ai 模块注册中心并周期重推自愈", 0,
                        TimeUnit.MINUTES.toMillis(intervalMinutes))
                        .category(TaskDefinition.CATEGORY_PLATFORM),
                this::pushSafely);
    }

    private void pushSafely() {
        try {
            push();
        } catch (Throwable e) {
            log.warn("[ai-api] 业务模块描述推送失败，将在下个周期重试: {}", e.getMessage());
        }
    }

    private void push() {
        List<AiBizModuleDescriptor> descriptors = new ArrayList<>();
        for (AiBizModuleProvider provider : providers) {
            try {
                List<AiBizModuleDescriptor> declared = provider.modules();
                if (declared != null) {
                    descriptors.addAll(declared);
                }
            } catch (Exception e) {
                log.warn("[ai-api] 收集业务模块描述失败，跳过: {} - {}", provider.getClass().getName(), e.getMessage());
            }
        }
        if (descriptors.isEmpty()) {
            return;
        }
        RemoteAiBizModuleRegistry registry = AiDubboBizModuleRegistryReference.proxy(environment);
        if (registry == null) {
            return; // 等待下个周期（mse-ai 未就绪）
        }
        String group = environment.getProperty("spring.application.name", "unknown");
        registry.register(group, descriptors);
        log.info("[ai-api] 已推送 {} 条业务模块描述至 mse-ai 模块注册中心（provider={}）", descriptors.size(), group);
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
        if (dubboPresent()) {
            AiDubboBizModuleRegistryReference.destroy();
        }
    }
}
