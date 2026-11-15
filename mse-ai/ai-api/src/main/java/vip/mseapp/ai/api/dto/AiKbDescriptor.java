package vip.mseapp.ai.api.dto;

import java.io.Serializable;

/**
 * 知识库声明描述（RPC 契约）：业务积木声明式注册的知识库元数据。
 *
 * <p>声明的是「库壳」而非文档内容：mse-ai 按编码在各租户世界幂等创建同名知识库
 * （默认切片参数，文档由各租户在知识库管理自行上传），供智能体声明的 kbCodes 引用绑定。
 */
public class AiKbDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库编码（世界内唯一，与 ai_knowledge_base.code 一致；智能体 kbCodes 引用此编码） */
    private String code;

    /** 名称 */
    private String name;

    /** 描述（管理页展示） */
    private String description;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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
}
