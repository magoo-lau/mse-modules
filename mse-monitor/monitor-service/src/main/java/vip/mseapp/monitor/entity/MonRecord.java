package vip.mseapp.monitor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 监控采样记录（每次采集一条，metrics 为按目标类型的指标集 JSON）。
 *
 * <p>保留策略：每日清理任务删除 retention-days（默认 7）之前的记录；趋势图按
 * (target_id, collect_time) 范围查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mon_record")
public class MonRecord extends BaseEntity {

    /** 监控目标 */
    private Long targetId;

    /** 采集时间 */
    private LocalDateTime collectTime;

    /** 采集状态：up/down */
    private String status;

    /** 响应耗时（ms） */
    private Integer latencyMs;

    /** 指标集 JSON（按类型：cpu_usage_pct/used_memory_pct/threads_connected/qps…） */
    private String metrics;
}
