package vip.mseapp.search.api;

import java.util.List;

/**
 * 可搜索源目录契约（mse-search 实现，业务侧推送器消费）。
 *
 * <p>业务侧由 search-api 的自动装配自动推送（见 support 包），无需手工调用。注册为幂等
 * upsert：按 sourceCode 新增或更新描述字段（sourceName/jumpUrlTemplate/permissionCode/
 * dataScopeEnabled/providerGroup）；扫描参数（scanCron/batchSize/shardCount/incrementalColumn）
 * 与启停状态为管理端资产，已存在的源不被覆盖。
 */
public interface RemoteSearchSourceService {

    /**
     * 批量注册可搜索源描述。
     *
     * @param descriptors 描述清单（逐条独立校验：不合法条目跳过并告警，不拖垮整批）
     */
    void register(List<SearchSourceDescriptor> descriptors);
}
