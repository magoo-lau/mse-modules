package vip.mseapp.search.engine;

import java.util.List;
import java.util.Map;

/**
 * 搜索引擎端口（引擎解耦点）：search-service 的索引写入/查询只依赖本接口，
 * 默认实现 {@link EsSearchEngineClient}（Elasticsearch）。更换引擎 = 声明自己的
 * 同接口 Bean（@ConditionalOnMissingBean(SearchEngineClient) 语义整体替换），业务零改动。
 *
 * <p>查询体为引擎无关的 JSON DSL（Map 结构，键与 ES Query DSL 对齐），由
 * {@code SearchQueryService} 组装三层过滤条件；结果为展平的 Map（total/hits/aggregations）。
 */
public interface SearchEngineClient {

    /** 索引不存在时按 settings/mappings 创建（存在则跳过） */
    void ensureIndex(String indexName, Map<String, Object> settings, Map<String, Object> mappings);

    /**
     * 增量更新索引 mapping（合并语义：允许新增字段；已有字段类型变更会被引擎拒绝，
     * 调用方需走全量重建）。mappings 结构与 {@link #ensureIndex} 的 mappings 一致。
     */
    void putMapping(String indexName, Map<String, Object> mappings);

    /** 批量 upsert（_id 幂等覆盖） */
    void bulkIndex(String indexName, List<EngineDoc> docs);

    /** 批量删除 */
    void bulkDelete(String indexName, List<String> docIds);

    /** 条件删除（孤儿文档清理），返回删除条数 */
    long deleteByQuery(String indexName, Map<String, Object> query);

    /** 检索：requestBody = {query, from, size, highlight, aggs, track_total_hits} */
    Map<String, Object> search(List<String> indexNames, Map<String, Object> requestBody);

    /** 强制段合并（索引优化，闲时执行） */
    void forceMerge(String indexName);

    /** 索引文档数（管理端水位/文档量展示） */
    long docCount(String indexName);

    /** 删除索引（全量重建先删后建；不存在返回 false） */
    boolean deleteIndex(String indexName);

    /** 列出匹配模式的索引（管理端索引运维：清单/健康/文档量/大小），pattern = 引擎通配式（如 mse-search-*） */
    List<IndexInfo> listIndices(String pattern);

    /** 连通性探测 */
    boolean ping();

    /**
     * 引擎文档（_id + 源字段）。
     */
    record EngineDoc(String id, Map<String, Object> source) {
    }

    /**
     * 索引清单条目（cat indices 视图；关闭索引 docsCount 为空时计 0）。
     */
    record IndexInfo(String indexName, String health, String status, long docsCount, String storeSize) {
    }
}
