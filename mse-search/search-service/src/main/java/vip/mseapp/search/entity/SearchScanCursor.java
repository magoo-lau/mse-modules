package vip.mseapp.search.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 扫描游标（租户隔离表）：租户 × 源 × 分片粒度的增量水位，watermark 为增量列下界
 * （首次全量 = "0"）。停机补跑由调度器按 cron 锚点推算。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("search_scan_cursor")
public class SearchScanCursor extends BaseEntity {

    /** 源标识 */
    private String sourceCode;

    /** 分片号（0 起） */
    private Integer shardIndex;

    /** 增量水位下界 */
    private String watermark;

    /** 最近扫描时间（调度 cron 锚点） */
    private LocalDateTime lastScanTime;

    /** 连续失败次数（告警观测用） */
    private Integer failCount;

    /** 最近一次失败原因 */
    private String lastError;
}
