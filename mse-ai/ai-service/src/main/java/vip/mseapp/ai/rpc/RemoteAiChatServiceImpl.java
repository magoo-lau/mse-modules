package vip.mseapp.ai.rpc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.mseapp.ai.api.RemoteAiChatService;
import vip.mseapp.ai.api.dto.AiChatRequest;
import vip.mseapp.ai.api.dto.AiChatResult;
import vip.mseapp.ai.entity.AiChatMessage;
import vip.mseapp.ai.entity.AiChatSession;
import vip.mseapp.ai.mapper.AiChatMessageMapper;
import vip.mseapp.ai.mapper.AiChatSessionMapper;
import vip.mseapp.ai.mapper.AiUsageLogMapper;
import vip.mseapp.ai.service.AiChatService;
import vip.mseapp.ai.service.DbChatMemory;
import vip.mseapp.ai.service.UsageRecorder;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;

/**
 * AI 对话契约实现：其他业务服务经 @RpcReference 消费。
 *
 * <p>无租户归属的 RPC 调用按调用方透传的租户上下文校验可见范围（Dubbo Filter 写入
 * TenantContext）；conversationId 传空走无状态单轮（不建会话、不挂记忆），
 * 传会话 ID 则续接多轮上下文。
 */
@RpcService(RemoteAiChatService.class)
public class RemoteAiChatServiceImpl implements RemoteAiChatService {

    private final AiChatService chatService;
    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AiUsageLogMapper usageLogMapper;
    private final UsageRecorder usageRecorder;
    private final DbChatMemory chatMemory;

    public RemoteAiChatServiceImpl(AiChatService chatService, AiChatSessionMapper sessionMapper,
                                   AiChatMessageMapper messageMapper, AiUsageLogMapper usageLogMapper,
                                   UsageRecorder usageRecorder, DbChatMemory chatMemory) {
        this.chatService = chatService;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.usageLogMapper = usageLogMapper;
        this.usageRecorder = usageRecorder;
        this.chatMemory = chatMemory;
    }

    @Override
    public AiChatResult chat(AiChatRequest request) {
        if (request == null || request.getAgentCode() == null || request.getAgentCode().isBlank()) {
            throw new ServiceException(400, "agentCode 不能为空");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ServiceException(400, "message 不能为空");
        }
        Long tenantId = TenantContext.getRequiredTenantId();
        Long userId = UserContext.getUserId();

        AiChatService.ResolvedChat ctx;
        boolean stateless = request.getConversationId() == null || request.getConversationId().isBlank();
        if (stateless) {
            ctx = chatService.prepareStateless(request.getAgentCode(), tenantId, userId);
        } else {
            ctx = chatService.prepare(request.getAgentCode(), request.getConversationId(),
                    request.getMessage(), tenantId, userId, "rpc");
        }

        long start = System.currentTimeMillis();
        try {
            // 引用来源收集（KbRagAdvisor 检索回调；工作流编排智能体不产生，保持 null）
            List<vip.mseapp.ai.kb.KbRetrievalService.KbHit> hits = new java.util.ArrayList<>();
            String content = chatService.chat(ctx, request.getMessage(), request.getVariables(), hits::addAll);
            AiChatResult result = new AiChatResult();
            result.setContent(content);
            result.setModel(ctx.model());
            result.setConversationId(stateless ? ctx.sessionId() : request.getConversationId());
            if (!hits.isEmpty()) {
                List<vip.mseapp.ai.api.dto.AiChatSource> sources = new java.util.ArrayList<>();
                for (int i = 0; i < hits.size(); i++) {
                    vip.mseapp.ai.kb.KbRetrievalService.KbHit hit = hits.get(i);
                    vip.mseapp.ai.api.dto.AiChatSource source = new vip.mseapp.ai.api.dto.AiChatSource();
                    source.setIndex(i + 1);
                    source.setChunkId(hit.chunkId() == null ? "" : hit.chunkId());
                    source.setFileName(hit.fileName() == null ? "" : hit.fileName());
                    source.setScore(hit.score());
                    String excerpt = hit.content();
                    source.setExcerpt(excerpt == null ? ""
                            : excerpt.substring(0, Math.min(200, excerpt.length())));
                    sources.add(source);
                }
                result.setSources(sources);
            }
            return result;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "AI 调用失败：" + e.getMessage());
        }
    }

    /** 会话消息回放（供消费方展示历史；仅会话归属租户可读）。随会话行世界路由（与写入链同世界） */
    public List<AiChatMessage> messages(String conversationId) {
        AiChatSession session = TenantContext.runAs(TenantContext.getRequiredTenantId(), () ->
                chatMemory.requireSession(conversationId));
        if (!TenantContext.getRequiredTenantId().equals(session.getTenantId())) {
            throw new ServiceException(403, "无权读取该会话");
        }
        return TenantContext.runAs(session.getTenantId(), () -> messageMapper.selectList(
                new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getSessionId, conversationId)
                        .orderByAsc(AiChatMessage::getId)));
    }

    @SuppressWarnings("unused")
    private void noop() {
        // 保留引用避免未使用告警：usageRecorder/usageLogMapper 供扩展（按请求维度补流水）
    }
}
