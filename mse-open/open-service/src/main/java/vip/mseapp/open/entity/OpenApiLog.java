package vip.mseapp.open.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 开放 API 调用审计日志（全局表）。每次 /open/api/** 调用一条，含鉴权失败与限流拒绝。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("open_api_log")
public class OpenApiLog extends BaseEntity {

    /** 应用编号（未通过 token 校验时为空） */
    private Long appId;

    /** 命中的 API 标识（未匹配到清单或未授权时可能为空） */
    private String apiCode;

    /** HTTP 方法 */
    private String method;

    /** 请求路径 */
    private String path;

    /** 调用方 IP（优先 X-Forwarded-For 首跳） */
    private String ip;

    /** 响应 HTTP 状态码 */
    private Integer statusCode;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 失败原因（鉴权失败/限流/业务异常摘要，成功为空） */
    private String errorMsg;

    /** 链路追踪 ID */
    private String traceId;
}
