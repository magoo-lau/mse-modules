package vip.mseapp.search.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 搜索引擎任务（租户隔离表，按归属租户创建与执行）：全量重建 / 索引优化 / 立即扫描。
 * idleOnly=1 的重任务仅在闲时窗口（mse.search.idle-window）内执行，工作时间触发则挂起等待。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("search_task")
public class SearchTask extends BaseEntity {

    /** 任务类型常量：全量重建（水位重置重扫 + 完成后清孤儿文档） */
    public static final String TYPE_FULL_REBUILD = "FULL_REBUILD";

    /** 任务类型：索引优化（force-merge，闲时执行） */
    public static final String TYPE_INDEX_OPTIMIZE = "INDEX_OPTIMIZE";

    /** 任务类型：立即增量扫描（不受闲时窗口限制） */
    public static final String TYPE_SCAN_NOW = "SCAN_NOW";

    /** 任务类型：FULL_REBUILD / INDEX_OPTIMIZE / SCAN_NOW */
    private String taskType;

    /** 目标源（SCAN_NOW / FULL_REBUILD 必填；INDEX_OPTIMIZE 可空 = 全部索引） */
    private String sourceCode;

    /** 是否闲时专用：0 立即 1 仅闲时窗口内执行 */
    private Integer idleOnly;

    /** 状态：0 待执行 1 执行中 2 成功 3 失败 */
    private Integer status;

    /** 进度百分比（0-100） */
    private Integer progress;

    /** 处理文档数 */
    private Long processedRows;

    /** 任务参数（JSON，预留） */
    private String payload;

    /** 失败原因 */
    private String errorMsg;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 状态：待执行 */
    public static final int STATUS_PENDING = 0;

    /** 状态：执行中 */
    public static final int STATUS_RUNNING = 1;

    /** 状态：成功 */
    public static final int STATUS_SUCCESS = 2;

    /** 状态：失败 */
    public static final int STATUS_FAILED = 3;
}
