package vip.mseapp.search.api.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import vip.mseapp.search.api.RemoteSearchSourceService;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import vip.mseapp.rpc.dubbo.DubboRuntimeConfigs;

/**
 * Dubbo 注册引用持有（仅供 {@link SearchSourceRegistrationPusher} 在 dubbo 模式下加载，
 * 宿主无 Dubbo 时本类不被类加载）。编程式 ReferenceConfig 创建 RemoteSearchSourceService 代理，
 * check=false 启动不依赖 mse-search 就绪。
 */
final class SearchDubboRegistryReference {

    private static final Logger log = LoggerFactory.getLogger(SearchDubboRegistryReference.class);

    private static volatile ReferenceConfig<RemoteSearchSourceService> reference;

    private SearchDubboRegistryReference() {
    }

    /** 创建代理；注册中心不可用/对端未就绪返回 null（调用方下个周期重试） */
    static RemoteSearchSourceService proxy(Environment environment) {
        ReferenceConfig<RemoteSearchSourceService> config = reference;
        if (config == null) {
            synchronized (SearchDubboRegistryReference.class) {
                if (reference == null) {
                    ReferenceConfig<RemoteSearchSourceService> created = new ReferenceConfig<>();
                    created.setInterface(RemoteSearchSourceService.class);
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
            log.warn("[mse-search] 搜索引擎源目录引用暂不可用（将重试）: {}", e.getMessage());
            return null;
        }
    }

    static void destroy() {
        ReferenceConfig<RemoteSearchSourceService> config = reference;
        if (config != null) {
            try {
                config.destroy();
            } catch (Exception ignored) {
                // 下线阶段的清理失败无需处理
            }
        }
    }
}
