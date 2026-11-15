package vip.mseapp.ai.api.dto;

import java.io.Serializable;

/**
 * AI 工具描述（RPC 契约）：提供给模型的功能元数据。
 */
public class AiToolDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 工具编码（全局唯一，与 ai_tool.tool_code 一致） */
    private String toolCode;

    /** 工具名称（面向模型，简短动宾结构） */
    private String name;

    /** 功能说明（面向模型：何时该调用本工具） */
    private String description;

    /** 入参 JSON Schema（{"type":"object","properties":{...},"required":[...]}；无参工具传 {}） */
    private String parametersJsonSchema;

    public String getToolCode() {
        return toolCode;
    }

    public void setToolCode(String toolCode) {
        this.toolCode = toolCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getParametersJsonSchema() {
        return parametersJsonSchema;
    }

    public void setParametersJsonSchema(String parametersJsonSchema) {
        this.parametersJsonSchema = parametersJsonSchema;
    }
}
