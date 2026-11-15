package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 数据服务 API 调用日志（审计每次开放调用）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_api_log")
public class DatApiLog extends BaseEntity {

    /** 状态：成功 */
    public static final String STATUS_SUCCESS = "success";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "failed";

    /** API 编号 */
    private Long apiId;

    /** 应用编号 */
    private Long appId;

    /** 本次请求参数 JSON */
    private String params;

    /** 返回行数 */
    private Integer rowCount;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 状态：success / failed */
    private String status;

    /** 错误信息 */
    private String errorMsg;

    /** 调用方 IP */
    private String clientIp;
}
