package vip.mseapp.ai.api.dto;

import java.io.Serializable;
import java.util.Map;

/**
 * AI 对话请求（RPC 契约）。
 */
public class AiChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体编码（ai_agent.agent_code，必填） */
    private String agentCode;

    /** 用户消息 */
    private String message;

    /** 会话 ID（可选：传入则续接多轮上下文并落库；为空则单轮无状态） */
    private String conversationId;

    /** 提示词模板变量（可选，渲染智能体 system_prompt 中的 {{var}} 占位） */
    private Map<String, String> variables;

    public String getAgentCode() {
        return agentCode;
    }

    public void setAgentCode(String agentCode) {
        this.agentCode = agentCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables;
    }
}
