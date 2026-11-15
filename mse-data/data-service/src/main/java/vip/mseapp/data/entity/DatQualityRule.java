package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据质量规则：对源表/贴源表做周期性检测并产出报告。
 *
 * <p>规则类型：
 * not_null 非空 / unique 唯一 / range 数值区间 / enum 枚举值 / custom_sql 自定义坏数据查询 /
 * row_count_fluctuation 行数波动（对比上次报告行数 ± 百分比）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_quality_rule")
public class DatQualityRule extends BaseEntity {

    public static final String TYPE_NOT_NULL = "not_null";
    public static final String TYPE_UNIQUE = "unique";
    public static final String TYPE_RANGE = "range";
    public static final String TYPE_ENUM = "enum";
    public static final String TYPE_CUSTOM_SQL = "custom_sql";
    public static final String TYPE_FLUCTUATION = "row_count_fluctuation";

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 规则名称 */
    private String name;

    /** 数据源（0 = 中台库贴源层） */
    private Long datasourceId;

    /** 目标表（中台库仅 ods_ 前缀） */
    private String targetTable;

    /** 规则类型 */
    private String ruleType;

    /** 检测列（custom_sql / fluctuation 无需） */
    private String columnName;

    /** 规则配置 JSON（min/max/values/sql/thresholdPct） */
    private String ruleConfig;

    /** 调度 cron（空=仅手动） */
    private String cron;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 最近执行时间 */
    private LocalDateTime lastRunTime;

    /** 最近结果：0 未通过 1 通过 */
    private Integer lastPass;

    /** 最近通过率（%） */
    private BigDecimal lastPassRate;

    private String remark;
}
