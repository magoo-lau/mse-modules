package vip.mseapp.search.dispatch;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.api.RemoteSearchDataProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 增量数据提供者分发器：按源的 providerGroup 寻址业务 Provider 实现，mse-search 不依赖业务 jar。
 *
 * <ul>
 *   <li>dubbo 模式（微服务）：经 {@link SearchDubboGenericInvoker} 泛化调用业务服务
 *       （本类保持零 Dubbo import，与基座同款探测门控）；</li>
 *   <li>local 模式（单体聚合）：容器按 providerGroup() 匹配实现后直调
 *       （业务 api jar 就在 classpath，Provider Bean 经业务自动装配注册）。</li>
 * </ul>
 */
@Component
public class SearchProviderDispatcher {

    private static final int RPC_TIMEOUT_MILLIS = 30_000;

    private final ConfigurableListableBeanFactory beanFactory;
    private final Environment environment;

    /** group → 本地实现或失败哨兵（避免每次扫描重复探测） */
    private final Map<String, Object> localResolved = new ConcurrentHashMap<>();

    private static final String FAIL_PREFIX = "!";

    public SearchProviderDispatcher(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    /**
     * 调用业务 Provider 拉取一批文档。
     *
     * @param providerGroup 源登记的业务服务名（spring.application.name）
     * @param requestJson   FetchBatchRequest JSON
     * @return FetchBatchResult JSON
     */
    public String fetch(String providerGroup, String requestJson) {
        if (providerGroup == null || providerGroup.isBlank()) {
            throw new ServiceException(500, "源未登记 providerGroup，无法寻址数据提供者");
        }
        if ("dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"))
                && dubboPresent()) {
            Object result = SearchDubboGenericInvoker.invoke(RemoteSearchDataProvider.class.getName(),
                    providerGroup, "fetchBatch", requestJson, environment, RPC_TIMEOUT_MILLIS);
            return String.valueOf(result);
        }
        return invokeLocal(providerGroup, requestJson);
    }

    /**
     * 调用业务 Provider 拉取字段目录（可选契约：业务未实现/未提供时返回 null）。
     * 失败以 ServiceException 抛出，由调用方决定降级展示。
     *
     * @param providerGroup 源登记的业务服务名（spring.application.name）
     * @param requestJson   请求 JSON：{"sourceCode":"..."}
     * @return SearchFieldCatalogResult JSON；null = 业务未提供目录
     */
    public String catalog(String providerGroup, String requestJson) {
        if (providerGroup == null || providerGroup.isBlank()) {
            throw new ServiceException(500, "源未登记 providerGroup，无法寻址数据提供者");
        }
        if ("dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"))
                && dubboPresent()) {
            Object result = SearchDubboGenericInvoker.invoke(RemoteSearchDataProvider.class.getName(),
                    providerGroup, "fieldCatalog", requestJson, environment, RPC_TIMEOUT_MILLIS);
            return result == null ? null : String.valueOf(result);
        }
        RemoteSearchDataProvider provider = resolveLocal(providerGroup);
        return provider.fieldCatalog(requestJson);
    }

    private boolean dubboPresent() {
        try {
            Class.forName("org.apache.dubbo.config.ReferenceConfig");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private RemoteSearchDataProvider resolveLocal(String providerGroup) {
        Object resolved = localResolved.computeIfAbsent(providerGroup, group -> {
            Map<String, RemoteSearchDataProvider> candidates = beanFactory.getBeansOfType(RemoteSearchDataProvider.class);
            for (RemoteSearchDataProvider candidate : candidates.values()) {
                if (group.equals(candidate.providerGroup())) {
                    return candidate;
                }
            }
            return FAIL_PREFIX + "容器内未找到 providerGroup=" + group
                    + " 的 RemoteSearchDataProvider 实现（检查业务方 providerGroup() 是否返回服务名）";
        });
        if (resolved instanceof String fail) {
            throw new ServiceException(500, fail.substring(FAIL_PREFIX.length()));
        }
        return (RemoteSearchDataProvider) resolved;
    }

    private String invokeLocal(String providerGroup, String requestJson) {
        return resolveLocal(providerGroup).fetchBatch(requestJson);
    }
}
