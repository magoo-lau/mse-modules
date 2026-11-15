package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量报告（每次规则执行的检测结果）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_quality_report")
public class DatQualityReport extends BaseEntity {

    /** 状态：通过 */
    public static final String STATUS_SUCCESS = "success";
    /** 状态：未通过 */
    public static final String STATUS_FAILED = "failed";

    /** 规则编号 */
    private Long ruleId;

    /** 规则名冗余 */
    private String ruleName;

    /** 状态：success / failed */
    private String status;

    /** 检测总行数 */
    private Long totalRows;

    /** 坏数据行数 */
    private Long badRows;

    /** 通过率（%） */
    private BigDecimal passRate;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 错误信息 */
    private String errorMsg;

    /** 触发方式：manual / sched */
    private String triggerType;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}
