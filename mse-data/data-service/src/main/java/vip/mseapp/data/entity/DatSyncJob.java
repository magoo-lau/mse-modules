package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 数据集成同步任务（源端 → 中台 ODS 贴源表）。
 *
 * <p>目标 ODS 表由引擎动态创建（ods_ 前缀，统一附 tenant_id 列）；
 * 全量模式清空本租户行后重灌，增量模式按水位列（时间戳/自增 ID）拉取。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_sync_job")
public class DatSyncJob extends BaseEntity {

    /** 来源：表 */
    public static final String SOURCE_TABLE = "table";
    /** 来源：自定义查询 SQL */
    public static final String SOURCE_SQL = "sql";

    /** 模式：全量（清空重灌） */
    public static final String MODE_FULL = "full";
    /** 模式：增量（水位） */
    public static final String MODE_INCR = "incr";

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 任务名称 */
    private String name;

    /** 来源数据源 */
    private Long datasourceId;

    /** 来源类型：table 表 / sql 自定义查询 */
    private String sourceType;

    /** 源表名（source_type=table） */
    private String sourceTable;

    /** 自定义查询 SQL（source_type=sql，仅 SELECT，:param 占位不支持参数） */
    private String querySql;

    /** 目标 ODS 表（引擎自动建，ods_ 前缀） */
    private String targetTable;

    /** 模式：full 全量 / incr 增量 */
    private String syncMode;

    /** 增量水位列（时间戳/自增 ID） */
    private String incrColumn;

    /** 当前水位值（引擎回写） */
    private String watermark;

    /** 调度 cron（Spring 表达式，空=仅手动） */
    private String cron;

    /** 前置任务（成功完成后级联触发本任务） */
    private Long depJobId;

    /** 最近一次同步行数 */
    private Long rowCount;

    /** 最近执行时间 */
    private LocalDateTime lastRunTime;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    private String remark;
}
