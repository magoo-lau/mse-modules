package vip.mseapp.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索引擎参数（mse.search.*，Nacos mse-search.yaml 管理）。
 */
@Component
@ConfigurationProperties(prefix = "mse.search")
public class SearchProperties {

    /** 索引名前缀（索引名 = index-prefix + "-" + sourceCode 小写） */
    private String indexPrefix = "mse-search";

    /** 中文分析器（建索引 settings 使用；ES 自带 smartcn，可换已安装的 ik_max_word 等） */
    private String analyzer = "smartcn";

    /** 闲时窗口（逗号分段 HH:mm-HH:mm，支持跨天如 22:00-06:00；闲时任务仅窗口内执行） */
    private String idleWindow = "00:00-07:00";

    private final Scan scan = new Scan();

    private final Task task = new Task();

    private final Field field = new Field();

    /** 增量扫描调度参数 */
    public static class Scan {
        /** 调度开关（多实例部署靠 LockOps 防重入，无需关闭） */
        private boolean enabled = true;

        /** 扫描引擎初始化延迟毫秒（等中间件就绪） */
        private long initialDelayMs = 30_000;

        /** 调度轮询间隔毫秒 */
        private long pollIntervalMs = 20_000;

        /** 同一批次间隔毫秒（限流，0 = 不等待；闲时窗口内自动放宽为 0） */
        private long batchIntervalMs = 500;

        /** 单次扫描每源最大批次数（防雪崩） */
        private int maxBatchesPerTick = 200;

        /** 源未配置 cron 时的默认周期（每 10 分钟） */
        private String defaultCron = "0 */10 * * * ?";

        /** 默认每批条数 */
        private int defaultBatchSize = 500;

        /** 默认分片数 */
        private int defaultShardCount = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public long getBatchIntervalMs() {
            return batchIntervalMs;
        }

        public void setBatchIntervalMs(long batchIntervalMs) {
            this.batchIntervalMs = batchIntervalMs;
        }

        public int getMaxBatchesPerTick() {
            return maxBatchesPerTick;
        }

        public void setMaxBatchesPerTick(int maxBatchesPerTick) {
            this.maxBatchesPerTick = maxBatchesPerTick;
        }

        public String getDefaultCron() {
            return defaultCron;
        }

        public void setDefaultCron(String defaultCron) {
            this.defaultCron = defaultCron;
        }

        public int getDefaultBatchSize() {
            return defaultBatchSize;
        }

        public void setDefaultBatchSize(int defaultBatchSize) {
            this.defaultBatchSize = defaultBatchSize;
        }

        public int getDefaultShardCount() {
            return defaultShardCount;
        }

        public void setDefaultShardCount(int defaultShardCount) {
            this.defaultShardCount = defaultShardCount;
        }
    }

    /** 闲时任务调度参数 */
    public static class Task {
        /** 任务调度开关 */
        private boolean enabled = true;

        /** 任务调度初始化延迟毫秒 */
        private long initialDelayMs = 30_000;

        /** 任务调度轮询间隔毫秒 */
        private long pollIntervalMs = 30_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public void setInitialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
        }

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }
    }

    /** 字段配置参数（mapping 膨胀保护） */
    public static class Field {
        /** 单源字段数上限（平台配置 ∪ 租户新增的并集口径） */
        private int maxPerSource = 100;

        /** 单源单租户可新增字段数上限（mapping 并集为平台全局资产，防单租户占满） */
        private int tenantMaxAdded = 50;

        public int getMaxPerSource() {
            return maxPerSource;
        }

        public void setMaxPerSource(int maxPerSource) {
            this.maxPerSource = maxPerSource;
        }

        public int getTenantMaxAdded() {
            return tenantMaxAdded;
        }

        public void setTenantMaxAdded(int tenantMaxAdded) {
            this.tenantMaxAdded = tenantMaxAdded;
        }
    }

    public String getIndexPrefix() {
        return indexPrefix;
    }

    public void setIndexPrefix(String indexPrefix) {
        this.indexPrefix = indexPrefix;
    }

    public String getAnalyzer() {
        return analyzer;
    }

    public void setAnalyzer(String analyzer) {
        this.analyzer = analyzer;
    }

    public String getIdleWindow() {
        return idleWindow;
    }

    public void setIdleWindow(String idleWindow) {
        this.idleWindow = idleWindow;
    }

    public Scan getScan() {
        return scan;
    }

    public Task getTask() {
        return task;
    }

    public Field getField() {
        return field;
    }
}
