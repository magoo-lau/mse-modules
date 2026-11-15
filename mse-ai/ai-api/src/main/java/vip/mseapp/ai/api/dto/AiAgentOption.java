package vip.mseapp.ai.api.dto;

import java.io.Serializable;

/**
 * 智能体选项（RPC 契约，业务服务「场景 → 智能体」映射下拉数据源）。
 */
public class AiAgentOption implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体编码（ai_agent.agent_code，全局唯一；映射关系存储该值） */
    private String agentCode;

    /** 名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 归属：platform 平台公共 / tenant 租户自有 */
    private String scope;

    /** 头像（emoji 字面量或站内/外图片 URL，展示用；消费方按 http(s)/站内路径前缀判断是否图片） */
    private String avatar;

    public String getAgentCode() {
        return agentCode;
    }

    public void setAgentCode(String agentCode) {
        this.agentCode = agentCode;
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

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
}
