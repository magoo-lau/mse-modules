package vip.mseapp.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 监控引擎参数（mse.monitor.*，Nacos mse-monitor.yaml 管理）。
 *
 * <p>告警收件人/Webhook 等通知设置不在此处——存 mon_setting 表，监控中心界面维护。
 */
@Component
@ConfigurationProperties(prefix = "mse.monitor")
public class MonitorProperties {

    /**
     * 离线模式（租户私有部署监控自管标记，缺省 false=在线）。
     *
     * <p>在线（缺省）：监控由平台运营——平台端查看/维护，采集数据按目标归属分库
     * （平台目标落平台库）；租户端菜单虽双端存在，但由人工控制不授权给租户（默认套餐
     * 不含监控页面），界面不出现、数据不可见。
     *
     * <p>离线（true，租户离线私有部署形态）：监控中心整体退化为「本地单一监控世界」——
     * mon_* 全部读写不再路由平台库、不再按会话租户过滤（runIgnore 直连本地缺省库），
     * 采集/查询/告警/通知全走本地离线库，本机 OS 目标亦落本地；租户授权监控页面后
     * 即在租户端维护本地监控。平台库已注册的形态下该标记同样生效（监控弃用平台库）。
     */
    private boolean offline = false;

    /** 调度轮询间隔（毫秒）：每轮扫描到期目标提交采集 */
    private long scanIntervalMs = 10_000;

    /** 新目标默认采集间隔（秒） */
    private int defaultCollectInterval = 30;

    /** 采样记录保留天数（每日清理任务删除更早记录） */
    private int retentionDays = 7;

    /** 采集线程池并行度 */
    private int executorParallelism = 4;

    /**
     * 目标服务 actuator 端点鉴权令牌（与服务侧 mse.actuator.auth.token 同源，经
     * ${MSE_ACTUATOR_TOKEN:} 环境变量占位天然对齐）；空 = 目标服务未开启 actuator 鉴权，
     * 探针匿名拉取（兼容未开启鉴权的存量部署）
     */
    private String actuatorToken = "";

    private final Probe probe = new Probe();

    private final Redis redis = new Redis();

    public static class Probe {
        /** 单次探测超时（毫秒，HTTP 连接/读写、Redis/RocketMQ 命令共用） */
        private int timeoutMs = 5_000;

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class Redis {
        /** 缓存监控键浏览单次 SCAN 收集的键数量上限（保护大库，超出置 capped 标志提示缩小匹配模式） */
        private int scanLimit = 5_000;

        /** 慢命令抓取单轮 SLOWLOG GET 条数上限（抓取开启期间每轮采集读取，只读命令） */
        private int slowlogMaxEntries = 10;

        public int getScanLimit() {
            return scanLimit;
        }

        public void setScanLimit(int scanLimit) {
            this.scanLimit = scanLimit;
        }

        public int getSlowlogMaxEntries() {
            return slowlogMaxEntries;
        }

        public void setSlowlogMaxEntries(int slowlogMaxEntries) {
            this.slowlogMaxEntries = Math.max(1, slowlogMaxEntries);
        }
    }

    public boolean isOffline() {
        return offline;
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public int getDefaultCollectInterval() {
        return defaultCollectInterval;
    }

    public void setDefaultCollectInterval(int defaultCollectInterval) {
        this.defaultCollectInterval = defaultCollectInterval;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public int getExecutorParallelism() {
        return executorParallelism;
    }

    public void setExecutorParallelism(int executorParallelism) {
        this.executorParallelism = executorParallelism;
    }

    public String getActuatorToken() {
        return actuatorToken;
    }

    public void setActuatorToken(String actuatorToken) {
        this.actuatorToken = actuatorToken;
    }

    public Probe getProbe() {
        return probe;
    }

    public Redis getRedis() {
        return redis;
    }
}
