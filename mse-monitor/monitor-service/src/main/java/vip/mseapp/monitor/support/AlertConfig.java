package vip.mseapp.monitor.support;

import tools.jackson.databind.ObjectMapper;

/**
 * 告警规则（mon_target.alert_config 的 JSON 视图）。
 *
 * <p>未配置的字段不参与评估；down 缺省开启（探测失败即告警）。
 */
public class AlertConfig {

    /** 探测失败（DOWN）是否告警 */
    private Boolean down = true;

    /** 响应耗时阈值（毫秒） */
    private Long latencyMs;

    /** CPU 使用率阈值（%，OS 目标） */
    private Double cpuPercent;

    /** 内存使用率阈值（%，OS 目标） */
    private Double memPercent;

    /** 磁盘使用率阈值（%，OS 目标，取全部挂载点最大值） */
    private Double diskPercent;

    /** Redis 内存使用率阈值（%，maxmemory>0 时才评估） */
    private Double redisMemPercent;

    /** MySQL 连接数阈值（Threads_connected） */
    private Integer mysqlMaxThreads;

    /** JVM 堆内存使用率阈值（%，jvm 目标） */
    private Double jvmHeapPercent;

    /** 每次采集新增慢 SQL 条数阈值（slowsql 目标，按 slowsql_rate 评估） */
    private Integer slowSqlCount;

    /** Redis 慢命令条数阈值（redis 目标，仅慢命令抓取开启时按本轮 slow_cmds 条数评估） */
    private Integer redisSlowCmdCount;

    public static AlertConfig parse(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return new AlertConfig();
        }
        try {
            return mapper.readValue(json, AlertConfig.class);
        } catch (Exception e) {
            return new AlertConfig();
        }
    }

    public Boolean getDown() {
        return down;
    }

    public void setDown(Boolean down) {
        this.down = down;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Double getCpuPercent() {
        return cpuPercent;
    }

    public void setCpuPercent(Double cpuPercent) {
        this.cpuPercent = cpuPercent;
    }

    public Double getMemPercent() {
        return memPercent;
    }

    public void setMemPercent(Double memPercent) {
        this.memPercent = memPercent;
    }

    public Double getDiskPercent() {
        return diskPercent;
    }

    public void setDiskPercent(Double diskPercent) {
        this.diskPercent = diskPercent;
    }

    public Double getRedisMemPercent() {
        return redisMemPercent;
    }

    public void setRedisMemPercent(Double redisMemPercent) {
        this.redisMemPercent = redisMemPercent;
    }

    public Integer getMysqlMaxThreads() {
        return mysqlMaxThreads;
    }

    public void setMysqlMaxThreads(Integer mysqlMaxThreads) {
        this.mysqlMaxThreads = mysqlMaxThreads;
    }

    public Double getJvmHeapPercent() {
        return jvmHeapPercent;
    }

    public void setJvmHeapPercent(Double jvmHeapPercent) {
        this.jvmHeapPercent = jvmHeapPercent;
    }

    public Integer getSlowSqlCount() {
        return slowSqlCount;
    }

    public void setSlowSqlCount(Integer slowSqlCount) {
        this.slowSqlCount = slowSqlCount;
    }

    public Integer getRedisSlowCmdCount() {
        return redisSlowCmdCount;
    }

    public void setRedisSlowCmdCount(Integer redisSlowCmdCount) {
        this.redisSlowCmdCount = redisSlowCmdCount;
    }
}
