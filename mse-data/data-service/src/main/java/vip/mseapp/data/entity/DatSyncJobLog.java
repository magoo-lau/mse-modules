package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 同步任务运行日志。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_sync_job_log")
public class DatSyncJobLog extends BaseEntity {

    /** 触发：手动 */
    public static final String TRIGGER_MANUAL = "manual";
    /** 触发：调度 */
    public static final String TRIGGER_SCHED = "sched";

    /** 状态：执行中 */
    public static final String STATUS_RUNNING = "running";
    /** 状态：成功 */
    public static final String STATUS_SUCCESS = "success";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "failed";

    /** 任务编号 */
    private Long jobId;

    /** 触发方式：manual 手动 / sched 调度 */
    private String triggerType;

    /** 状态：running / success / failed */
    private String status;

    /** 本次执行模式：full / incr */
    private String syncMode;

    /** 同步行数 */
    private Long rowCount;

    /** 执行前水位 */
    private String watermarkUsed;

    /** 执行后水位 */
    private String watermarkNew;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 错误信息 */
    private String errorMsg;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}
