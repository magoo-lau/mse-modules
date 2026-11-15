package vip.mseapp.ai.api;

import vip.mseapp.ai.api.dto.AiChatRequest;
import vip.mseapp.ai.api.dto.AiChatResult;

/**
 * AI 对话服务契约（ai-service 实现）。
 *
 * <p>其他业务服务依赖 ai-api 后经 {@code @RpcReference} 注入即可调用平台 AI 能力：
 * 单体 local 模式本地直调，微服务 Dubbo 模式远程调用 mse-ai，消费方代码零改动。
 * 租户上下文经 RPC 隐式透传，契约方法无需携带 tenantId——平台按当前租户校验
 * 智能体可见范围与模型可用范围，并记录用量流水。
 */
public interface RemoteAiChatService {

    /**
     * 同步对话（非流式）。
     *
     * @param request 对话请求（agentCode + 用户消息 + 可选会话/变量）
     * @return 回复内容与 token 用量；智能体不存在/未发布时抛业务异常
     */
    AiChatResult chat(AiChatRequest request);
}
