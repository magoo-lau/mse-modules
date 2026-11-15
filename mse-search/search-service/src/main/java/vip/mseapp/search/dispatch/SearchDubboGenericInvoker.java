package vip.mseapp.search.dispatch;

import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.rpc.service.GenericService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.rpc.dubbo.DubboRuntimeConfigs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dubbo 分组泛化调用通道（仅供 {@link SearchProviderDispatcher} 在 dubbo 模式下加载）。
 *
 * <p>增量数据提供者契约在多个业务服务各有一个实现（group = 业务服务名，参照 mse-data 导出
 * RemoteAiTool 的同款分组导出），按 (接口名, group) 创建并缓存泛化引用，String 进 String 出
 * 零 POJO 转换。
 */
final class SearchDubboGenericInvoker {

    private static final Logger log = LoggerFactory.getLogger(SearchDubboGenericInvoker.class);

    /** 接口全名#group → 泛化引用（创建失败不缓存，可重试） */
    private static final Map<String, GenericService> REFERENCES = new ConcurrentHashMap<>();

    private SearchDubboGenericInvoker() {
    }

    static Object invoke(String interfaceName, String group, String methodName, String requestJson,
                         Environment environment, int timeoutMillis) {
        String cacheKey = interfaceName + "#" + group;
        GenericService service = REFERENCES.get(cacheKey);
        if (service == null) {
            try {
                ReferenceConfig<GenericService> config = new ReferenceConfig<>();
                config.setInterface(interfaceName);
                config.setGeneric("true");
                config.setGroup(group);
                config.setCheck(false);
                config.setTimeout(timeoutMillis);
                config.setApplication(DubboRuntimeConfigs.application(environment));
                config.setRegistry(DubboRuntimeConfigs.registry(environment));
                service = config.get();
                REFERENCES.put(cacheKey, service);
                log.info("[mse-search] 创建泛化引用: {} (group={})", interfaceName, group);
            } catch (Exception e) {
                throw new ServiceException(503, "增量数据提供者暂不可用（" + group + "）: " + e.getMessage());
            }
        }
        try {
            return service.$invoke(methodName, new String[]{"java.lang.String"}, new Object[]{requestJson});
        } catch (Exception e) {
            throw new ServiceException(500, cleanRemoteMessage(e.getMessage()));
        }
    }

    /** 泛化异常 message 形如 "xxx.SomeException: 具体原因"，剥掉异常类前缀保留业务语义 */
    private static String cleanRemoteMessage(String message) {
        if (message == null || message.isBlank()) {
            return "增量数据拉取失败";
        }
        int idx = message.indexOf(": ");
        if (idx > 0 && idx <= 120 && message.indexOf('.') < idx) {
            return message.substring(idx + 2);
        }
        return message;
    }
}
