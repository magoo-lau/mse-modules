package vip.mseapp.ai.api.dto;

import java.io.Serializable;

/**
 * 业务模块描述（RPC 契约）：业务积木声明式注册的「业务模块」元数据——mse-ai 通用业务
 * 分析入口（biz_query 路由门面）按此把问题路由到归属模块的查询工具，查询在模块自身
 * 进程内执行（平台侧零业务库连接）。
 */
public class AiBizModuleDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模块编码（世界内唯一，如 oa/mes/mp；biz_query 的 module 入参取值） */
    private String moduleCode;

    /** 模块名称（如「协同办公」「制造执行」，面向模型与用户展示） */
    private String name;

    /** 功能描述（模块业务范围一句话，供模型判定问题归属） */
    private String description;

    /** 归属服务名（spring.application.name，即模块查询工具的 Dubbo group 与登记溯源 rpc_group） */
    private String serviceName;

    /** 领域关键词（逗号分隔，如「审批,流程,请假」；供模型与检索判定问题归属） */
    private String keywords;

    /** 业务表前缀（逗号分隔小写，如「oa_」；模块查询工具的放行白名单口径） */
    private String tablePrefixes;

    /** 模块查询工具编码（ai_tool.tool_code，如 oa_biz_query；biz_query 按 (serviceName, toolCode) 转发） */
    private String toolCode;

    public String getModuleCode() {
        return moduleCode;
    }

    public void setModuleCode(String moduleCode) {
        this.moduleCode = moduleCode;
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

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getTablePrefixes() {
        return tablePrefixes;
    }

    public void setTablePrefixes(String tablePrefixes) {
        this.tablePrefixes = tablePrefixes;
    }

    public String getToolCode() {
        return toolCode;
    }

    public void setToolCode(String toolCode) {
        this.toolCode = toolCode;
    }
}
