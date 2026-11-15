package vip.mseapp.search.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.mq.TenantEvent;
import vip.mseapp.search.api.SearchIndexPayload;
import vip.mseapp.search.api.SearchIndexTopics;
import vip.mseapp.search.service.SearchIndexService;
import vip.mseapp.search.support.SearchJson;

/**
 * 索引事件消费者（实时增量通道）：业务写操作后经 TenantEventSender 发送
 * doc_upsert / doc_delete 事件，本监听器从消息体恢复租户上下文后落索引。
 *
 * <p>仅在配置了 rocketmq.name-server 时注册消费容器——未配置 MQ 的环境（如仅需
 * MySQL/Redis 的单体）跳过装配，增量由定时扫描兜底。目录真租户化后按事件租户的
 * 目录校验源：未登记抛错触发 MQ 重投（目录扇出播种 ≤ 一个推送周期后自愈）；
 * 已停用是租户的显式意志，告警后丢弃。
 */
@Component
@ConditionalOnProperty(prefix = "rocketmq", name = "name-server")
@RocketMQMessageListener(topic = SearchIndexTopics.TOPIC, consumerGroup = "mse-search-index-group")
public class SearchIndexEventListener implements RocketMQListener<TenantEvent> {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexEventListener.class);

    private final SearchIndexService indexService;

    public SearchIndexEventListener(SearchIndexService indexService) {
        this.indexService = indexService;
    }

    @Override
    public void onMessage(TenantEvent event) {
        SearchIndexPayload payload;
        try {
            payload = SearchJson.parse(event.payload(), SearchIndexPayload.class);
        } catch (Exception e) {
            log.warn("[mse-search] 索引事件载荷解析失败（丢弃）: type={}, payload={}", event.eventType(), event.payload());
            return;
        }
        TenantContext.runAs(event.tenantId(), () -> {
            try {
                if (SearchIndexTopics.EVENT_DOC_DELETE.equals(event.eventType()) || payload.isDeleted()) {
                    indexService.applyDelete(event.tenantId(), payload);
                } else if (SearchIndexTopics.EVENT_DOC_UPSERT.equals(event.eventType())) {
                    indexService.applyUpsert(event.tenantId(), payload);
                } else {
                    log.warn("[mse-search] 未知索引事件类型（丢弃）: {}", event.eventType());
                }
            } catch (Exception e) {
                // 源未登记等场景抛错会触发 MQ 重投；目录扇出播种周期自愈后重投成功
                throw new IllegalStateException("索引事件处理失败: " + e.getMessage(), e);
            }
        });
    }
}
