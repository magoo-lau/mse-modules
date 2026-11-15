package vip.mseapp.ai.autoconfigure;

import jakarta.annotation.PostConstruct;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import vip.mseapp.ai.api.RemoteAiAgentService;
import vip.mseapp.ai.api.RemoteAiChatService;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.RemoteAiToolRegistry;
import vip.mseapp.ai.mapper.AiUsageLogMapper;
import vip.mseapp.ai.mapper.AiChatMessageMapper;
import vip.mseapp.ai.mapper.AiChatSessionMapper;
import vip.mseapp.ai.api.AiAgentProvider;
import vip.mseapp.ai.api.RemoteAiAgentRegistry;
import vip.mseapp.ai.mapper.AiAgentMapper;
import vip.mseapp.ai.rpc.LocalAgentAutoRegistrar;
import vip.mseapp.ai.rpc.LocalBizModuleAutoRegistrar;
import vip.mseapp.ai.rpc.LocalKbDeclRegistrar;
import vip.mseapp.ai.rpc.LocalToolAutoRegistrar;
import vip.mseapp.ai.rpc.RemoteAiAgentRegistryImpl;
import vip.mseapp.ai.rpc.RemoteAiAgentServiceImpl;
import vip.mseapp.ai.rpc.RemoteAiBizModuleRegistryImpl;
import vip.mseapp.ai.rpc.RemoteAiChatServiceImpl;
import vip.mseapp.ai.rpc.RemoteAiKbRegistryImpl;
import vip.mseapp.ai.rpc.RemoteAiToolRegistryImpl;
import vip.mseapp.ai.service.AiAgentService;
import vip.mseapp.ai.service.AiChatService;
import vip.mseapp.ai.service.DbChatMemory;
import vip.mseapp.ai.service.UsageRecorder;
import vip.mseapp.redis.config.MseRedisAutoConfiguration.LockOps;
import vip.mseapp.mybatis.config.MseMybatisAutoConfiguration;
import vip.mseapp.ai.openapi.AiOpenApiContributor;
import vip.mseapp.core.annotation.TenantServiceModule;

/**
 * AI 平台业务服务自动装配（Spring Boot SPI）。
 *
 * <p>接入方仅需引入 ai-service 依赖：Service / 工厂 / 工具注册 / Mapper 均由此注册，
 * 无需 component-scan 业务包名，也无需 @MapperScan。控制器由
 * {@link AiControllerAutoConfiguration} 装配，两者均经
 * {@code META-INF/spring/...AutoConfiguration.imports} 登记。
 *
 * <p>RPC 契约实现采用 {@code @Bean + @ConditionalOnMissingBean} 显式注册：
 * 接入方声明自己的同契约 Bean 即可整体替换默认实现。
 */
@AutoConfiguration(before = MseMybatisAutoConfiguration.class,
        afterName = "vip.mseapp.fs.autoconfigure.FsServiceAutoConfiguration",
        beforeName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@MapperScan(basePackages = "vip.mseapp.ai.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.ai.service", "vip.mseapp.ai.tool", "vip.mseapp.ai.kb",
        "vip.mseapp.ai.mcpclient", "vip.mseapp.ai.open", "vip.mseapp.ai.support", "vip.mseapp.ai.config",
        "vip.mseapp.ai.workflow"})
@TenantServiceModule("mse-ai")
public class AiServiceAutoConfiguration {

    // ===== 知识库原文件归档存储：mse-fs 可用时优先（归档串 fs:{fileId}），本地目录兜底 =====
    // 两侧条件互斥（不可依赖嵌套类声明顺序：javac 编译出的 InnerClasses 顺序不保序，
    // Local 先注册时 Fs 的 @ConditionalOnMissingBean 会让位，归档将静默落本地目录）。

    @Configuration(proxyBeanMethods = false)
    static class FsKbStorageConfiguration {

        @Bean
        @Conditional(FsAvailableCondition.class)
        @ConditionalOnMissingBean(vip.mseapp.ai.kb.KbStorageService.class)
        public vip.mseapp.ai.kb.KbStorageService fsKbStorageService(
                vip.mseapp.ai.service.AiPlatformProperties properties) {
            return new vip.mseapp.ai.kb.FsKbStorageService(
                    new vip.mseapp.ai.kb.LocalKbStorageService(properties.getKbStoragePath()));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalKbStorageConfiguration {

        @Bean
        @Conditional(FsUnavailableCondition.class)
        @ConditionalOnMissingBean(vip.mseapp.ai.kb.KbStorageService.class)
        public vip.mseapp.ai.kb.KbStorageService localKbStorageService(
                vip.mseapp.ai.service.AiPlatformProperties properties) {
            return new vip.mseapp.ai.kb.LocalKbStorageService(properties.getKbStoragePath());
        }
    }

    // ===== 智能体头像存储：mse-fs 可用时走公开素材通道（/fs/asset/...），本地目录兜底 =====
    // 两侧条件互斥（同上，不可依赖嵌套类声明顺序）。

    @Configuration(proxyBeanMethods = false)
    static class FsAgentAvatarConfiguration {

        @Bean
        @Conditional(AvatarFsCondition.class)
        @ConditionalOnMissingBean(vip.mseapp.ai.avatar.AgentAvatarStorage.class)
        public vip.mseapp.ai.avatar.AgentAvatarStorage fsAgentAvatarStorage() {
            return new vip.mseapp.ai.avatar.FsAgentAvatarStorage();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalAgentAvatarConfiguration {

        @Bean
        @Conditional(AvatarLocalCondition.class)
        @ConditionalOnMissingBean(vip.mseapp.ai.avatar.AgentAvatarStorage.class)
        public vip.mseapp.ai.avatar.AgentAvatarStorage localAgentAvatarStorage(
                vip.mseapp.ai.service.AiPlatformProperties properties) {
            return new vip.mseapp.ai.avatar.LocalAgentAvatarStorage(properties.getAvatarStoragePath());
        }
    }

    /**
     * spring.ai.* 自动装配模型的工具调用管理器（排在 Spring AI ToolCallingAutoConfiguration
     * 之前，其 {@code @ConditionalOnMissingBean} 让位）：「平台默认模型」兜底路径（agent 未绑定
     * 模型时 {@code AiModelFactory.getChatModel(null)} 回退 starter 装配的 ChatModel）与模型中心
     * 建档模型共用同一套上限（mse.ai.tool-max-*）与超限中文改写，不再落回库默认
     * 单工具 40/合计 150 + 硬编码英文文案。无自动装配模型的环境多一个轻量 Bean，无副作用。
     */
    @Bean
    @ConditionalOnMissingBean(ToolCallingManager.class)
    public ToolCallingManager toolCallingManager(vip.mseapp.ai.service.AiPlatformProperties properties) {
        return vip.mseapp.ai.service.FriendlyToolCallingManager.fromProperties(properties);
    }

    /** 工具轨迹跨线程传播注册（超限拦截事件从模型内部线程回传对话链路用；幂等） */
    @Bean
    ToolTracePropagationRegistrar toolTracePropagationRegistrar() {
        return new ToolTracePropagationRegistrar();
    }

    static class ToolTracePropagationRegistrar {

        @PostConstruct
        void register() {
            vip.mseapp.ai.support.ToolTracePropagation.register();
        }
    }

    /** 知识库异步解析线程池（文档解析/向量化重活与请求线程隔离；租户上下文显式传参不经 ThreadLocal） */
    @Bean(destroyMethod = "shutdown")
    public java.util.concurrent.ExecutorService aiKbParseExecutor(vip.mseapp.ai.service.AiPlatformProperties properties) {
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        return new java.util.concurrent.ThreadPoolExecutor(
                Math.max(1, properties.getKbParseWorkers()), Math.max(1, properties.getKbParseWorkers()),
                60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "mse-ai-kb-parse-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Bean
    @ConditionalOnMissingBean(RemoteAiChatService.class)
    public RemoteAiChatService remoteAiChatService(AiChatService chatService,
                                                   AiChatSessionMapper sessionMapper,
                                                   AiChatMessageMapper messageMapper,
                                                   AiUsageLogMapper usageLogMapper,
                                                   UsageRecorder usageRecorder,
                                                   DbChatMemory chatMemory) {
        return new RemoteAiChatServiceImpl(chatService, sessionMapper, messageMapper,
                usageLogMapper, usageRecorder, chatMemory);
    }

    /** 智能体选项列表契约实现（业务服务「场景 → 智能体」映射管理页的下拉数据源） */
    @Bean
    @ConditionalOnMissingBean(RemoteAiAgentService.class)
    public RemoteAiAgentService remoteAiAgentService(AiAgentService agentService) {
        return new RemoteAiAgentServiceImpl(agentService);
    }

    /** 智能体声明自动登记（启动扫描容器内 AiAgentProvider Bean 幂等 upsert 平台行；多实例单飞，租户副本由可见性入口懒补齐） */
    @Bean
    public LocalAgentAutoRegistrar localAgentAutoRegistrar(ObjectProvider<AiAgentProvider> providers,
                                                           AiAgentMapper agentMapper,
                                                           ObjectProvider<LockOps> lockOps) {
        return new LocalAgentAutoRegistrar(providers, agentMapper, lockOps);
    }

    /** 智能体登记契约实现（业务服务推送器经 Dubbo 调用，把智能体声明 upsert 到 ai_agent；带窗口节流与变更比对） */
    @Bean
    @ConditionalOnMissingBean(RemoteAiAgentRegistry.class)
    public RemoteAiAgentRegistry remoteAiAgentRegistry(LocalAgentAutoRegistrar localAgentAutoRegistrar,
                                                       ObjectProvider<LockOps> lockOps) {
        return new RemoteAiAgentRegistryImpl(localAgentAutoRegistrar, lockOps);
    }

    /** 知识库声明自动登记（启动扫描容器内 AiKbProvider Bean 幂等 upsert 平台行；多实例单飞，库壳由副本补齐时按编码懒建） */
    @Bean
    public LocalKbDeclRegistrar localKbDeclRegistrar(ObjectProvider<vip.mseapp.ai.api.AiKbProvider> providers,
                                                      vip.mseapp.ai.mapper.AiKbDeclMapper kbDeclMapper,
                                                      ObjectProvider<LockOps> lockOps) {
        return new LocalKbDeclRegistrar(providers, kbDeclMapper, lockOps);
    }

    /** 知识库声明登记契约实现（业务服务推送器经 Dubbo 调用，把知识库声明 upsert 到 ai_kb_decl；带窗口节流与变更比对） */
    @Bean
    @ConditionalOnMissingBean(vip.mseapp.ai.api.RemoteAiKbRegistry.class)
    public vip.mseapp.ai.api.RemoteAiKbRegistry remoteAiKbRegistry(LocalKbDeclRegistrar localKbDeclRegistrar,
                                                                    ObjectProvider<LockOps> lockOps) {
        return new RemoteAiKbRegistryImpl(localKbDeclRegistrar, lockOps);
    }

    /** 工具登记契约实现（业务服务推送器经 Dubbo 调用，把工具描述 upsert 到 ai_tool；带窗口节流与变更比对） */
    @Bean
    @ConditionalOnMissingBean(RemoteAiToolRegistry.class)
    public RemoteAiToolRegistry remoteAiToolRegistry(vip.mseapp.ai.mapper.AiToolMapper toolMapper,
                                                     ObjectProvider<LockOps> lockOps) {
        return new RemoteAiToolRegistryImpl(toolMapper, lockOps);
    }

    /** 系统工具自动登记（启动扫描容器内 RemoteAiTool Bean 幂等 upsert；多实例单飞，存量 rpc 行描述字段一并同步自愈） */
    @Bean
    public LocalToolAutoRegistrar localToolAutoRegistrar(ObjectProvider<RemoteAiTool> localTools,
                                                         vip.mseapp.ai.mapper.AiToolMapper toolMapper,
                                                         ObjectProvider<LockOps> lockOps) {
        return new LocalToolAutoRegistrar(localTools, toolMapper, lockOps);
    }

    /** 业务模块声明自动登记（启动扫描容器内 AiBizModuleProvider Bean 幂等 upsert 平台行；模块目录=全局目录，仅平台行） */
    @Bean
    public LocalBizModuleAutoRegistrar localBizModuleAutoRegistrar(ObjectProvider<vip.mseapp.ai.api.AiBizModuleProvider> providers,
                                                                   vip.mseapp.ai.mapper.AiBizModuleMapper moduleMapper,
                                                                   ObjectProvider<LockOps> lockOps) {
        return new LocalBizModuleAutoRegistrar(providers, moduleMapper, lockOps);
    }

    /** 业务模块登记契约实现（业务服务推送器经 Dubbo 调用，把模块声明 upsert 到 ai_biz_module；带窗口节流与变更比对） */
    @Bean
    @ConditionalOnMissingBean(vip.mseapp.ai.api.RemoteAiBizModuleRegistry.class)
    public vip.mseapp.ai.api.RemoteAiBizModuleRegistry remoteAiBizModuleRegistry(
            LocalBizModuleAutoRegistrar localBizModuleAutoRegistrar,
            ObjectProvider<LockOps> lockOps) {
        return new RemoteAiBizModuleRegistryImpl(localBizModuleAutoRegistrar, lockOps);
    }

    /** 开放能力声明（开放平台自注册；按 Bean 名判重，避免多积木同类型贡献者互相让位） */
    @Bean(name = "aiOpenApiContributor")
    @ConditionalOnMissingBean(name = "aiOpenApiContributor")
    public vip.mseapp.open.api.OpenApiContributor aiOpenApiContributor() {
        return new AiOpenApiContributor();
    }
}
