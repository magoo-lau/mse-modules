package vip.mseapp.monitor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 监控告警（状态机：firing 触发中 → resolved 已恢复；target_name 冗余存储防目标删除后失联）。
 *
 * <p>同一目标同一规则 (target_id, rule_key) 同时至多一条 firing：触发时新建 + 通知，
 * 持续触发只推进 last_trigger_time（可配 repeat_notify_minutes 周期重发），恢复时转 resolved
 * 并通知恢复，手动处理即立即 resolved。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mon_alert")
public class MonAlert extends BaseEntity {

    public static final String STATUS_FIRING = "firing";
    public static final String STATUS_RESOLVED = "resolved";

    public static final String LEVEL_ERROR = "error";
    public static final String LEVEL_WARN = "warn";

    public static final String RULE_DOWN = "down";
    public static final String RULE_LATENCY = "latency";
    public static final String RULE_CPU = "cpu";
    public static final String RULE_MEM = "mem";
    public static final String RULE_DISK = "disk";
    public static final String RULE_REDIS_MEM = "redis_mem";
    public static final String RULE_MYSQL_THREADS = "mysql_threads";
    public static final String RULE_JVM_HEAP = "jvm_heap";
    public static final String RULE_SLOW_SQL = "slow_sql";
    public static final String RULE_REDIS_SLOW_CMD = "redis_slow_cmd";

    /** 监控目标 */
    private Long targetId;

    /** 目标名称（冗余，目标删除后告警仍可读） */
    private String targetName;

    /** 级别：warn/error（down 一律 error，其余 warn） */
    private String level;

    /** 规则键：down/latency/cpu/mem/disk/redis_mem/mysql_threads/jvm_heap/slow_sql/redis_slow_cmd */
    private String ruleKey;

    /** 告警内容（人类可读，通知正文） */
    private String content;

    /** 状态：firing 触发中 / resolved 已恢复 */
    private String status;

    /** 首次触发时间 */
    private LocalDateTime firstTriggerTime;

    /** 最近触发时间（持续触发/周期重发时推进） */
    private LocalDateTime lastTriggerTime;

    /** 恢复时间 */
    private LocalDateTime resolveTime;

    /** 通知结果 JSON（inbox/webhook 各自 ok 或失败原因） */
    private String notifyResult;
}
