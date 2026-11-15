package vip.mseapp.open.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.open.config.OpenProperties;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开放 API 泛化分发器：按目录 target（契约接口全名#方法名）调用业务契约，mse-open 不依赖业务 jar。
 *
 * <p>契约方法签名统一 {@code String 方法名(String requestJson)}（机器语义，见 open-api 模块）：
 * <ul>
 *   <li>dubbo 模式（微服务）：经 {@link OpenDubboGenericInvoker} 泛化调用业务服务（本类保持
 *       零 Dubbo import，与基座 RpcServiceExportBeanPostProcessor 同款探测门控）；</li>
 *   <li>local 模式（单体聚合）：容器解析契约唯一实现后反射调用（业务 api jar 就在 classpath）。</li>
 * </ul>
 */
public class OpenRpcDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OpenRpcDispatcher.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final Environment environment;
    private final OpenProperties properties;

    /** target → 反射 Method 或失败原因哨兵（避免每请求重复探测） */
    private final Map<String, Object> localResolved = new ConcurrentHashMap<>();

    /** local 解析失败哨兵前缀（与 Method 类型二分） */
    private static final String FAIL_PREFIX = "!";

    public OpenRpcDispatcher(ConfigurableListableBeanFactory beanFactory, Environment environment,
                             OpenProperties properties) {
        this.beanFactory = beanFactory;
        this.environment = environment;
        this.properties = properties;
    }

    /**
     * 分发调用。
     *
     * @param target      目录 target（接口全名#方法名）
     * @param requestJson 合并后的请求参数 JSON
     * @return 契约返回值（约定为 JSON 字符串）
     */
    public Object invoke(String target, String requestJson) {
        int hashIdx = target.indexOf('#');
        if (hashIdx <= 0 || hashIdx == target.length() - 1) {
            throw new ServiceException(500, "分发目标格式非法: " + target);
        }
        String interfaceName = target.substring(0, hashIdx);
        String methodName = target.substring(hashIdx + 1);
        if (properties.getRpcAllowedPackages().stream().noneMatch(interfaceName::startsWith)) {
            throw new ServiceException(404, "分发目标不在白名单内: " + interfaceName);
        }
        if ("dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"))
                && dubboPresent()) {
            return OpenDubboGenericInvoker.invoke(interfaceName, methodName, requestJson,
                    environment, (int) properties.getRpcTimeoutMillis());
        }
        return invokeLocal(interfaceName, methodName, requestJson);
    }

    private boolean dubboPresent() {
        try {
            Class.forName("org.apache.dubbo.config.ReferenceConfig");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ==================== local 反射（单体聚合） ====================

    private Object invokeLocal(String interfaceName, String methodName, String requestJson) {
        Object resolved = localResolved.computeIfAbsent(interfaceName + "#" + methodName, key -> {
            Class<?> apiType;
            try {
                apiType = Class.forName(interfaceName, false, OpenRpcDispatcher.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                return FAIL_PREFIX + "契约接口不在 classpath: " + interfaceName;
            }
            Map<String, ?> candidates = beanFactory.getBeansOfType(apiType);
            if (candidates.isEmpty()) {
                return FAIL_PREFIX + "契约实现未装配: " + interfaceName;
            }
            if (candidates.size() > 1) {
                log.warn("[open-api] 契约 {} 存在多个实现 {}，分发拒绝", interfaceName, candidates.keySet());
                return FAIL_PREFIX + "契约存在多个实现: " + interfaceName;
            }
            try {
                return apiType.getMethod(methodName, String.class);
            } catch (NoSuchMethodException e) {
                return FAIL_PREFIX + "契约方法不存在或不符 String(String) 签名: " + key;
            }
        });
        if (resolved instanceof String fail) {
            throw new ServiceException(500, fail.substring(FAIL_PREFIX.length()));
        }
        Method method = (Method) resolved;
        Object targetBean = beanFactory.getBeansOfType(method.getDeclaringClass())
                .values().iterator().next();
        try {
            return method.invoke(targetBean, requestJson);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ServiceException(500, String.valueOf(cause.getMessage()));
        } catch (Exception e) {
            throw new ServiceException(500, "开放调用失败: " + e.getMessage());
        }
    }
}
