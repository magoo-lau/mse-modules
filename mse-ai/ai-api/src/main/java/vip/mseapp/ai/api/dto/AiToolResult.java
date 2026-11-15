package vip.mseapp.ai.api.dto;

import java.io.Serializable;

/**
 * AI 工具执行结果（RPC 契约）。
 */
public class AiToolResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否执行成功（false 时 content 为错误说明，交由模型决策） */
    private boolean success;

    /** 结果内容（文本，将作为工具调用结果回传给模型） */
    private String content;

    public static AiToolResult ok(String content) {
        AiToolResult result = new AiToolResult();
        result.success = true;
        result.content = content;
        return result;
    }

    public static AiToolResult fail(String message) {
        AiToolResult result = new AiToolResult();
        result.success = false;
        result.content = message;
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
