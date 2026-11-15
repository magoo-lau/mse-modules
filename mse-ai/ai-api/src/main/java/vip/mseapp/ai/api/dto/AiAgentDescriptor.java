package vip.mseapp.ai.api.dto;

import java.io.Serializable;

/**
 * 智能体描述（RPC 契约）：业务积木声明式注册的智能体元数据。
 */
public class AiAgentDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体编码（世界内唯一，与 ai_agent.agent_code 一致；平台登记行供各租户播种副本） */
    private String agentCode;

    /** 名称 */
    private String name;

    /** 头像（可空：内置 emoji / mse-fs 公开素材地址 / 本地兜底地址；仅登记首建填充，此后管理侧权威） */
    private String avatar;

    /** 功能描述（智能体管理列表展示） */
    private String description;

    /** 系统提示词（simple 编排；支持 {{var}} 占位） */
    private String systemPrompt;

    /** 工具编码列表（逗号分隔 ai_tool.tool_code，可空） */
    private String toolCodes;

    /**
     * 声明绑定知识库编码列表（逗号分隔 ai_knowledge_base.code，可空）：各租户世界按编码
     * 幂等建库壳（声明见 AiKbProvider）并解析为本世界库 id 写入副本 kb_ids，实现声明式
     * RAG 绑定；文档内容仍由各租户在知识库管理上传（空库检索静默无结果，不阻断对话）。
     */
    private String kbCodes;

    /**
     * 直出图表模式声明默认值（可空=关）：开启后对话运行时在系统提示词末尾追加
     * 「一句话极简结论 + 图表中文列」输出纪律，查询结果交由前端数据卡片直出；
     * 平台行随提供方刷新，租户副本可在智能体管理改。
     */
    private Boolean chartDirect;

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

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getToolCodes() {
        return toolCodes;
    }

    public void setToolCodes(String toolCodes) {
        this.toolCodes = toolCodes;
    }

    public String getKbCodes() {
        return kbCodes;
    }

    public void setKbCodes(String kbCodes) {
        this.kbCodes = kbCodes;
    }

    public Boolean getChartDirect() {
        return chartDirect;
    }

    public void setChartDirect(Boolean chartDirect) {
        this.chartDirect = chartDirect;
    }
}
