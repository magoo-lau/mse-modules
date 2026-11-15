package vip.mseapp.ai.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import org.apache.dubbo.config.ReferenceConfig;
import vip.mseapp.ai.api.RemoteAiToolRegistry;
import vip.mseapp.rpc.dubbo.DubboRuntimeConfigs;

/**
 * Dubbo 登记引用持有（仅供 {@link AiToolRegistrationPusher} 在 dubbo 模式下加载，
 * 宿主无 Dubbo 时本类不被类加载）。编程式 ReferenceConfig 创建 RemoteAiToolRegistry 代理，
 * check=false 启动不依赖 mse-ai 就绪；mse-ai 是该契约唯一提供方，无需 group。
 */
final class AiDubboToolRegistryReference {

    private static final Logger log = LoggerFactory.getLogger(AiDubboToolRegistryReference.class);

    private static volatile ReferenceConfig<RemoteAiToolRegistry> reference;

    private AiDubboToolRegistryReference() {
    }

    /** 创建代理；注册中心不可用/对端未就绪返回 null（调用方下个周期重试） */
    static RemoteAiToolRegistry proxy(Environment environment) {
        ReferenceConfig<RemoteAiToolRegistry> config = reference;
        if (config == null) {
            synchronized (AiDubboToolRegistryReference.class) {
                if (reference == null) {
                    ReferenceConfig<RemoteAiToolRegistry> created = new ReferenceConfig<>();
                    created.setInterface(RemoteAiToolRegistry.class);
                    created.setCheck(false);
                    created.setTimeout(3000);
                    created.setApplication(DubboRuntimeConfigs.application(environment));
                    created.setRegistry(DubboRuntimeConfigs.registry(environment));
                    reference = created;
                }
                config = reference;
            }
        }
        try {
            return config.get();
        } catch (Exception e) {
            log.warn("[ai-api] mse-ai 工具登记引用暂不可用（将重试）: {}", e.getMessage());
            return null;
        }
    }

    static void destroy() {
        ReferenceConfig<RemoteAiToolRegistry> config = reference;
        if (config != null) {
            try {
                config.destroy();
            } catch (Exception ignored) {
                // 下线阶段的清理失败无需处理
            }
        }
    }
}
