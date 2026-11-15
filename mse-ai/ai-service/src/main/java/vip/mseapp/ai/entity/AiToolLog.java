package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.core.annotation.TenantDatasource;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 工具调用审计（租户业务表）：每次工具执行一条，含入参/出参/耗时/异常。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantDatasource
@TableName("ai_tool_log")
public class AiToolLog extends BaseEntity {

    /** 工具编码 */
    private String toolCode;

    /** 智能体编码 */
    private String agentCode;

    /** 会话 ID */
    private String sessionId;

    /** 入参 JSON */
    private String arguments;

    /** 出参内容 */
    private String result;

    /** 是否成功：0 成功 1 失败 */
    private Integer success;

    /** 耗时毫秒 */
    private Long durationMs;

    /** 失败原因 */
    private String errorMsg;
}
