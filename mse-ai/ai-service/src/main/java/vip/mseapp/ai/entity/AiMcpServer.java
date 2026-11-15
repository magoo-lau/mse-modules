package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 外部 MCP Server 注册（租户自管：各会话世界自有行，租户会话=本租户行、平台会话=平台世界行
 * tenant_id=0）：动态接入其工具供智能体挂载。
 *
 * <p>工具编码约定：mcp:{serverName}:{toolName}（智能体 tool_codes 直接填写该编码）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_mcp_server")
public class AiMcpServer extends BaseEntity {

    /** 服务名（唯一；工具编码引用） */
    private String name;

    /** 基础地址（如 http://mcp.example.com） */
    private String endpoint;

    /** SSE 路径（transport=sse 时使用，默认 /sse） */
    private String ssePath;

    /** 传输方式：sse（Streamable HTTP 后续按需扩展） */
    private String transport;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
