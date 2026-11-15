package vip.mseapp.search.api;

/**
 * 增量数据提供者契约（业务积木实现，mse-search 泛化消费）。
 *
 * <p>机器语义（与开放平台契约同款约定）：方法签名统一 {@code String fetchBatch(String requestJson)}，
 * 请求/响应均为 JSON（结构见 dto 包：FetchBatchRequest / FetchBatchResult）。mse-search 的
 * 扫描调度按源配置周期调用本方法拉取增量/全量文档批次，业务零被依赖。
 *
 * <p>实现要点：
 * <ul>
 *   <li>扫描按「租户 × 源」执行——请求携带 tenantId（本批目标租户），实现必须按其过滤数据
 *       （如查询加 tenant_id 条件），返回文档的 tenantId 与之一致（mse-search 写入侧按当前
 *       扫描租户二次过滤兜底，多拉的异租户文档会被丢弃）；</li>
 *   <li>增量：按 FetchBatchRequest.watermark 对增量列（默认 update_time）做下界过滤，
 *       返回 nextWatermark 供游标推进；首次全量 watermark 为 "0"（游标按租户 × 源独立记账，
 *       新租户目录首扫即全量）；</li>
 *   <li>分片：按 bizId 散列满足 {@code mod(bizId, shardCount) == shardIndex}（或等价 SQL），
 *       多分片并行拉取互不重叠；</li>
 *   <li>逻辑删除的行：deleted=true 返回（搜索引擎据此删文档）；MP 逻辑删除不更新
 *       update_time，删除务必同时走 {@link SearchIndexTopics} MQ 事件兜底；</li>
 *   <li>{@link #providerGroup()} 必须覆写返回业务服务名（spring.application.name）——
 *       dubbo 模式按此 group 寻址，单体聚合形态按此分派本地实现。</li>
 * </ul>
 */
public interface RemoteSearchDataProvider {

    /** 本实现所属业务服务名（spring.application.name；dubbo 导出 group 与此一致） */
    default String providerGroup() {
        return null;
    }

    /**
     * 拉取一批文档。
     *
     * @param requestJson FetchBatchRequest JSON
     * @return FetchBatchResult JSON（nextWatermark + hasMore 驱动游标推进）
     */
    String fetchBatch(String requestJson);

    /**
     * 业务字段目录（可选实现）：暴露本服务可被检索匹配的字段元数据清单，供 mse-search
     * 管理端/租户端按需拉取勾选（目录不落库，实时反映业务侧最新定义，如表单发布后的控件清单）。
     *
     * <p>默认返回 null = 业务未提供目录（mse-search 侧降级提示，不影响源与字段配置的既有功能）。
     *
     * @param requestJson 请求 JSON：{"sourceCode":"..."}（按源返回目录；业务也可忽略该参数返回全量）
     * @return SearchFieldCatalogResult JSON（entries 可收录字段 + conflicts 类型冲突排除清单），
     *         null = 未提供目录
     */
    default String fieldCatalog(String requestJson) {
        return null;
    }
}
