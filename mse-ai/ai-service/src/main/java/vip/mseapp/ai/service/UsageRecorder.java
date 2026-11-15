package vip.mseapp.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.entity.AiUsageLog;
import vip.mseapp.ai.mapper.AiUsageLogMapper;
import vip.mseapp.core.context.TenantContext;

/**
 * 用量流水记录：网关 / RPC / 开放 API 三通道统一落 ai_usage_log。
 *
 * <p>调用方可能位于 Reactor / Dubbo / 编排线程，租户归属以显式 tenantId 锚定
 * {@link TenantContext#runAs} 随世界路由落库（一切皆租户：独立库租户落专属库、
 * 共享租户落绑定共享库、平台世界 0 落平台库/降级主库）。
 */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final AiUsageLogMapper usageLogMapper;

    public UsageRecorder(AiUsageLogMapper usageLogMapper) {
        this.usageLogMapper = usageLogMapper;
    }

    public void record(Long tenantId, Long userId, String channel, String agentCode, String model,
                       String conversationId, Usage usage, long durationMs, boolean success, String errorMsg) {
        try {
            AiUsageLog record = new AiUsageLog();
            record.setTenantId(tenantId == null ? 0L : tenantId);
            record.setUserId(userId);
            record.setChannel(channel);
            record.setAgentCode(agentCode);
            record.setModel(model);
            record.setConversationId(conversationId);
            if (usage != null) {
                record.setPromptTokens(usage.getPromptTokens());
                record.setCompletionTokens(usage.getCompletionTokens());
                record.setTotalTokens(usage.getTotalTokens());
            }
            record.setDurationMs(durationMs);
            record.setStatus(success ? 0 : 1);
            record.setErrorMsg(errorMsg == null || errorMsg.length() <= 480 ? errorMsg
                    : errorMsg.substring(0, 480));
            TenantContext.runAs(record.getTenantId(), () -> usageLogMapper.insert(record));
        } catch (Exception e) {
            // 流水失败不影响主链路
            log.warn("[mse-ai] 用量流水写入失败: agent={}", agentCode, e);
        }
    }

    public void recordSimple(Long tenantId, Long userId, String channel, String agentCode, String model,
                             String conversationId, long durationMs, boolean success, String errorMsg) {
        record(tenantId, userId, channel, agentCode, model, conversationId, null,
                durationMs, success, errorMsg);
    }
}
