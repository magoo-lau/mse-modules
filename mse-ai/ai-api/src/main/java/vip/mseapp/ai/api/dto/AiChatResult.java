package vip.mseapp.ai.api.dto;

import java.io.Serializable;
import java.util.List;

/**
 * AI 对话结果（RPC 契约）。
 */
public class AiChatResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 回复内容 */
    private String content;

    /** 实际使用的模型标识 */
    private String model;

    /** 会话 ID（请求未传时平台生成，便于后续续接） */
    private String conversationId;

    /** 知识库引用来源（agent 绑定知识库且非工作流编排时返回；未绑定/编排智能体为 null） */
    private List<AiChatSource> sources;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    public List<AiChatSource> getSources() {
        return sources;
    }

    public void setSources(List<AiChatSource> sources) {
        this.sources = sources;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }
}
