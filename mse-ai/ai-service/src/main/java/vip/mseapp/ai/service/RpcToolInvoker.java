package vip.mseapp.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.core.exception.ServiceException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RPC 工具泛化调用器：按提供方服务名（Dubbo group）定向调用其 RemoteAiTool 实现。
 *
 * <p>业务服务导出方式见 ai-api 的 {@code RemoteAiTool} 注释——group=服务名，
 * 本类以 Dubbo 泛化引用（无需编译期依赖提供方实现）调用 describe()/invoke()，
 * 引用按 group 缓存，超时取 mse.ai.rpc-tool-timeout-millis。
 *
 * <p>本类签名保持零 Dubbo import：泛化引用全部收在嵌套类 {@link Holder} 中，
 * 仅在 dubbo 模式首次调用时类加载（单体 local 模式永不触达，无 classpath 要求）。
 */
@Component
public class RpcToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(RpcToolInvoker.class);

    public static final String INTERFACE_NAME = "vip.mseapp.ai.api.RemoteAiTool";

    private final Environment environment;
    private final AiPlatformProperties properties;
    private final Map<String, Boolean> warnedGroups = new ConcurrentHashMap<>();

    public RpcToolInvoker(Environment environment, AiPlatformProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    public AiToolResult invoke(String group, String toolCode, String argumentsJson) {
        requireDubboMode(group);
        return Holder.invoke(environment, properties, group, toolCode, argumentsJson);
    }

    private void requireDubboMode(String group) {
        if (!isDubboMode()) {
            if (warnedGroups.putIfAbsent(group, Boolean.TRUE) == null) {
                log.warn("[mse-ai] rpc 工具在 local 模式不可用: group={}", group);
            }
            throw new ServiceException(400, "rpc 工具仅在微服务（dubbo）模式可用: group=" + group);
        }
    }

    private boolean isDubboMode() {
        return "dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"));
    }

    /**
     * Dubbo 泛化引用持有者：本类仅在 dubbo 模式首次调用时被加载，
     * 其间 classpath 必有 Dubbo（optional 依赖在微服务部署由 ai-boot/dubbo starter 提供）。
     */
    private static final class Holder {

        private static final Map<String, org.apache.dubbo.rpc.service.GenericService> CACHE =
                new ConcurrentHashMap<>();

        static AiToolResult invoke(Environment env, AiPlatformProperties props, String group,
                                   String toolCode, String argumentsJson) {
            org.apache.dubbo.rpc.service.GenericService generic = reference(env, props, group);
            Object raw;
            try {
                // 固定走两参 invoke（RemoteAiTool#invoke(String,String)）：单工具提供方由默认方法透传，
                // 多工具提供方（如 mse-data 的 data_query/biz_query）按 toolCode 内部分发
                raw = generic.$invoke("invoke", new String[]{"java.lang.String", "java.lang.String"},
                        new Object[]{toolCode == null ? "" : toolCode, argumentsJson == null ? "{}" : argumentsJson});
            } catch (Exception e) {
                throw new ServiceException(500, "RPC 工具调用失败：" + e.getMessage());
            }
            return toResult(raw);
        }

        private static org.apache.dubbo.rpc.service.GenericService reference(
                Environment env, AiPlatformProperties props, String group) {
            return CACHE.computeIfAbsent(group, g -> {
                org.apache.dubbo.config.ReferenceConfig<org.apache.dubbo.rpc.service.GenericService> reference =
                        new org.apache.dubbo.config.ReferenceConfig<>();
                reference.setInterface(INTERFACE_NAME);
                reference.setGeneric(true);
                reference.setGroup(g);
                reference.setTimeout((int) props.getRpcToolTimeoutMillis());
                reference.setCheck(false);
                reference.setApplication(vip.mseapp.rpc.dubbo.DubboRuntimeConfigs.application(env));
                reference.setRegistry(vip.mseapp.rpc.dubbo.DubboRuntimeConfigs.registry(env));
                log.info("[mse-ai] 创建 RPC 工具泛化引用: interface={}, group={}", INTERFACE_NAME, g);
                return reference.get();
            });
        }

        private static AiToolResult toResult(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                return AiToolResult.fail("invoke 返回类型异常: "
                        + (raw == null ? "null" : raw.getClass().getName()));
            }
            AiToolResult result = new AiToolResult();
            Object success = map.get("success");
            Object content = map.get("content");
            result.setSuccess(Boolean.TRUE.equals(success) || "true".equals(String.valueOf(success)));
            result.setContent(content == null ? null : String.valueOf(content));
            return result;
        }
    }
}
