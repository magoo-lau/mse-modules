package vip.mseapp.open.dispatch;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.rpc.service.GenericService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import vip.mseapp.rpc.dubbo.DubboRuntimeConfigs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dubbo 泛化调用通道（仅供 {@link OpenRpcDispatcher} 在 dubbo 模式下加载，宿主无 Dubbo 时
 * 本类不被类加载）。按接口名创建 GenericService 引用并缓存，String 进 String 出零 POJO 转换。
 */
final class OpenDubboGenericInvoker {

    private static final Logger log = LoggerFactory.getLogger(OpenDubboGenericInvoker.class);

    /** 接口全名 → 泛化引用（创建失败不缓存，可重试） */
    private static final Map<String, GenericService> REFERENCES = new ConcurrentHashMap<>();

    private OpenDubboGenericInvoker() {
    }

    static Object invoke(String interfaceName, String methodName, String requestJson,
                         Environment environment, int timeoutMillis) {
        GenericService service = REFERENCES.get(interfaceName);
        if (service == null) {
            try {
                ReferenceConfig<GenericService> config = new ReferenceConfig<>();
                config.setInterface(interfaceName);
                config.setGeneric("true");
                config.setCheck(false);
                config.setTimeout(timeoutMillis);
                config.setApplication(DubboRuntimeConfigs.application(environment));
                config.setRegistry(DubboRuntimeConfigs.registry(environment));
                service = config.get();
                REFERENCES.put(interfaceName, service);
                log.info("[open-api] 创建泛化引用: {}", interfaceName);
            } catch (Exception e) {
                throw new vip.mseapp.core.exception.ServiceException(503, "开放目标服务暂不可用: " + e.getMessage());
            }
        }
        try {
            return service.$invoke(methodName, new String[]{"java.lang.String"}, new Object[]{requestJson});
        } catch (Exception e) {
            throw new vip.mseapp.core.exception.ServiceException(500, cleanRemoteMessage(e.getMessage()));
        }
    }

    /** 泛化异常 message 形如 "xxx.SomeException: 具体原因"，剥掉异常类前缀保留业务语义 */
    private static String cleanRemoteMessage(String message) {
        if (message == null || message.isBlank()) {
            return "开放调用失败";
        }
        int idx = message.indexOf(": ");
        if (idx > 0 && idx <= 120 && message.indexOf('.') < idx) {
            return message.substring(idx + 2);
        }
        return message;
    }
}
