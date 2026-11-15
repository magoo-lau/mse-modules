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

import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.RemoteAiToolRegistry;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.core.schedule.MseTaskScheduler;
import vip.mseapp.core.schedule.TaskDefinition;

/**
 * AI 工具自注册推送器：应用就绪后把容器内全部 {@link RemoteAiTool} 的描述推送至 mse-ai
 * 工具中心，并按周期重推自愈（mse-ai 未就绪/重启丢失后自动补登记）。
 *
 * <p>通道自检：宿主容器内已存在 {@link RemoteAiToolRegistry} 实现（单体聚合 ai-service 或
 * mse-ai 自身）时整体闲置——AiToolService 已直接收集本地 RemoteAiTool Bean，推送只会制造
 * 重复行；纯业务微服务（dubbo 模式）经 {@link AiDubboToolRegistryReference} 远推 mse-ai
 * （check=false 启动不依赖对方就绪）；local 模式且无实现说明工具无消费通道，告警一次后闲置。
 *
 * <p>调度经统一调度中心（taskId=ai-tool-registry-push：延迟启动 + 多实例防重）；容器内无
 * RemoteAiTool Bean 则整体闲置。本类保持零 Dubbo import（Dubbo 引用收在
 * {@link AiDubboToolRegistryReference}）。
 */
public class AiToolRegistrationPusher implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistrationPusher.class);

    private final ObjectProvider<RemoteAiTool> tools;
    private final Environment environment;
    private final org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory;
    private final long intervalMinutes;

    private final ObjectProvider<MseTaskScheduler> schedulerProvider;

    /** local 模式且宿主未聚合 mse-ai：工具无消费通道，告警一次后停推 */
    private final AtomicBoolean unconsumableWarned = new AtomicBoolean(false);

    public AiToolRegistrationPusher(ObjectProvider<RemoteAiTool> tools,
                                    Environment environment,
                                    org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory,
                                    ObjectProvider<MseTaskScheduler> schedulerProvider) {
        this.tools = tools;
        this.environment = environment;
        this.beanFactory = beanFactory;
        this.schedulerProvider = schedulerProvider;
        this.intervalMinutes = Math.max(1,
                environment.getProperty("mse.ai.tool-registry.interval-minutes", Long.class, 5L));
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (tools.stream().findAny().isEmpty()) {
            log.debug("[ai-api] 无 RemoteAiTool Bean，工具自注册推送器闲置");
            return;
        }
        if (!beanFactory.getBeansOfType(RemoteAiToolRegistry.class).isEmpty()) {
            log.info("[ai-api] 宿主容器内已有 RemoteAiToolRegistry 实现（本地收集已覆盖），工具推送器闲置");
            return;
        }
        if (!"dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local")) || !dubboPresent()) {
            if (unconsumableWarned.compareAndSet(false, true)) {
                log.warn("[ai-api] local 模式且未聚合 mse-ai，RemoteAiTool 工具无消费通道，跳过自注册推送");
            }
            return;
        }
        MseTaskScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            log.info("[ai-api] 调度器未装配（mse.scheduling.mode=none 等），工具自注册推送不启动");
            return;
        }
        scheduler.register(TaskDefinition.fixedDelay("ai-tool-registry-push", "AI 工具自注册推送",
                        "推送容器内 RemoteAiTool 描述至 mse-ai 工具中心并周期重推自愈", 0,
                        TimeUnit.MINUTES.toMillis(intervalMinutes))
                        .category(TaskDefinition.CATEGORY_PLATFORM),
                this::pushSafely);
    }

    private void pushSafely() {
        try {
            push();
        } catch (Throwable e) {
            log.warn("[ai-api] 工具描述推送失败，将在下个周期重试: {}", e.getMessage());
        }
    }

    private void push() {
        List<AiToolDescriptor> descriptors = new ArrayList<>();
        for (RemoteAiTool tool : tools) {
            try {
                descriptors.add(tool.describe());
            } catch (Exception e) {
                log.warn("[ai-api] 收集工具描述失败，跳过: {} - {}", tool.getClass().getName(), e.getMessage());
            }
        }
        if (descriptors.isEmpty()) {
            return;
        }
        RemoteAiToolRegistry registry = AiDubboToolRegistryReference.proxy(environment);
        if (registry == null) {
            return; // 等待下个周期（mse-ai 未就绪）
        }
        String group = environment.getProperty("spring.application.name", "unknown");
        registry.register(group, descriptors);
        log.info("[ai-api] 已推送 {} 条工具描述至 mse-ai 工具中心（provider={}）", descriptors.size(), group);
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
            AiDubboToolRegistryReference.destroy();
        }
    }
}
