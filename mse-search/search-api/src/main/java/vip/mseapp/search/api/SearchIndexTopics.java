package vip.mseapp.search.api;

/**
 * 索引事件约定（业务积木 → mse-search 的实时增量通道，经 common-mq TenantEventSender 发送）。
 *
 * <p>发送方式：{@code tenantEventSender.send(SearchIndexTopics.TOPIC, SearchIndexTopics.EVENT_DOC_UPSERT,
 * SearchIndexPayload)}，租户归属由 TenantEvent 封装自动携带；未配置 RocketMQ 的环境发送器为
 * noop 降级（事件丢弃），增量由定时扫描兜底。
 *
 * <p>payload 结构见 {@link SearchIndexPayload}；删除事件只需 sourceCode + bizId。
 */
public final class SearchIndexTopics {

    /** 索引事件 topic */
    public static final String TOPIC = "mse-search-index";

    /** 文档新增/修改（upsert） */
    public static final String EVENT_DOC_UPSERT = "doc_upsert";

    /** 文档删除（MP 逻辑删除不更新 update_time，删除必须走本事件兜底） */
    public static final String EVENT_DOC_DELETE = "doc_delete";

    private SearchIndexTopics() {
    }
}
