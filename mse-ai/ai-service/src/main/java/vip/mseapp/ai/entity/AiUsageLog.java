package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.core.annotation.TenantDatasource;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * AI 用量流水（租户业务表）：网关 / RPC / 开放 API 三通道调用统一记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantDatasource
@TableName("ai_usage_log")
public class AiUsageLog extends BaseEntity {

    /** 调用通道：gateway / rpc / openapi */
    private String channel;

    /** 智能体编码 */
    private String agentCode;

    /** 模型名 */
    private String model;

    /** 会话 ID */
    private String conversationId;

    /** 触发用户（开放 API 通道为空） */
    private Long userId;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    /** 耗时毫秒 */
    private Long durationMs;

    /** 结果：0 成功 1 失败 */
    private Integer status;

    /** 失败原因 */
    private String errorMsg;
}
