package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 工具登记（各世界自有行随世界路由；平台登记源 tenant_id=0 行由 LocalToolAutoRegistrar /
 * RemoteAiToolRegistryImpl 以 runIgnore 落 primary=平台库写入（0.1.0 起），兼作每租户播种的 0 行来源）。
 *
 * <p>来源 source：builtin 内置（随 ai-service，启动扫描自动登记）/ local 本地 Bean（单体聚合
 * 形态自动收集，启动扫描自动登记）/ rpc 远程契约（其他业务服务以 group=服务名导出 RemoteAiTool，
 * 此处登记 toolCode+group，dubbo 模式自动推送登记）/ http 通用 HTTP 接口 / dify Dify 应用
 * （后两者为手动登记的外部工具，连接配置存 config_json）/ mcp 外部 MCP Server 工具
 * （由 ai_mcp_server 动态接入，不在本表登记）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool")
public class AiTool extends BaseEntity {

    /** 工具编码（全局唯一） */
    private String toolCode;

    /** 工具名称 */
    private String name;

    /** 功能说明（面向模型） */
    private String description;

    /** 入参 JSON Schema */
    private String parametersJsonSchema;

    /** 来源：builtin / local / rpc / http / dify */
    private String source;

    /** RPC 提供方服务名（source=rpc 时即 Dubbo group） */
    private String rpcGroup;

    /** 外部工具连接配置（source=http/dify 时生效，JSON：url/method/headers 或 baseUrl/apiKey/appType 等） */
    private String configJson;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
