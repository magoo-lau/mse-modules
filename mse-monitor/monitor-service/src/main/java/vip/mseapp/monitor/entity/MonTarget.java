package vip.mseapp.monitor.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 监控目标（服务 / HTTP / JVM / 慢SQL / MySQL / Redis / RocketMQ / 操作系统）。
 *
 * <p>type=os 为本机目标（monitor-boot 所在主机，OSHI 采集），启动时自动确保存在；
 * 其余目标由管理端维护或 init.sql 预置本平台全家桶示例。status/lastLatencyMs 为最近一次
 * 采集结果快照，历史采样在 mon_record。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mon_target")
public class MonTarget extends BaseEntity {

    /** 类型：Java 微服务（拉 /actuator/health） */
    public static final String TYPE_SERVICE = "service";
    /** 类型：通用 HTTP（Nacos/ES/任意 URL） */
    public static final String TYPE_HTTP = "http";
    /** 类型：JVM（拉 /actuator/metrics，堆内存/GC/线程/进程CPU） */
    public static final String TYPE_JVM = "jvm";
    /** 类型：慢 SQL（拉 /actuator/slowsql，服务侧需开启 mse.mybatis.slow-sql.enabled） */
    public static final String TYPE_SLOWSQL = "slowsql";
    /** 类型：MySQL */
    public static final String TYPE_MYSQL = "mysql";
    /** 类型：Redis */
    public static final String TYPE_REDIS = "redis";
    /** 类型：RocketMQ */
    public static final String TYPE_ROCKETMQ = "rocketmq";
    /** 类型：操作系统（本机 OSHI） */
    public static final String TYPE_OS = "os";

    public static final String STATUS_UP = "up";
    public static final String STATUS_DOWN = "down";
    public static final String STATUS_UNKNOWN = "unknown";

    /** 状态：0 启用 1 停用（与基座 data 等业务表同款语义） */
    public static final int ENABLED = 0;
    public static final int DISABLED = 1;

    /** 目标名称 */
    private String name;

    /** 目标类型：service/http/jvm/slowsql/mysql/redis/rocketmq/os */
    private String targetType;

    /** 服务/HTTP 目标地址（http://host:port，service/jvm/slowsql 类型探测时拼 /actuator/...） */
    private String serviceUrl;

    /** 主机（mysql/redis/rocketmq 连接地址；os 固定本机） */
    private String host;

    /** 端口（mysql/redis/rocketmq） */
    private Integer port;

    /** 认证用户名（mysql；redis/rocketmq 可空） */
    private String username;

    /** 认证密码（mysql/redis；rocketmq 可空；查询时脱敏为 ******） */
    private String password;

    /** 采集间隔（秒，空=默认 30） */
    private Integer collectInterval;

    /** 状态：0 启用 1 停用 */
    private Integer enabled;

    /** 告警规则 JSON（AlertConfig：down/latencyMs/cpuPercent/memPercent/diskPercent/redisMemPercent/mysqlMaxThreads/jvmHeapPercent/slowSqlCount） */
    private String alertConfig;

    /** 最近状态：up/down/unknown */
    private String status;

    /** 最近状态时间 */
    private LocalDateTime statusTime;

    /** 最近响应耗时（ms） */
    private Integer lastLatencyMs;

    private String remark;
}
