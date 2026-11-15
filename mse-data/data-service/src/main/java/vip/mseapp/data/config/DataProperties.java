package vip.mseapp.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据中台配置（mse.data.*）。
 */
@Component
@ConfigurationProperties(prefix = "mse.data")
public class DataProperties {

    /** 敏感字段（数据源口令/appSecret）AES-GCM 落库加密密钥（生产必须修改） */
    private String cryptKey = "mse-data-default-crypt-key-please-change";

    /** 注册数据源连接池 */
    private Pool pool = new Pool();

    /** 同步引擎 */
    private Sync sync = new Sync();

    /** 开放 API */
    private Open open = new Open();

    /** 数据服务审批 */
    private Service service = new Service();

    public static class Pool {

        /** 单个注册数据源的连接池最大连接数 */
        private int maxPoolSize = 8;

        /** 池闲置回收秒数（超时未使用的池关闭，下次访问重建） */
        private int idleTimeoutSeconds = 300;

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getIdleTimeoutSeconds() {
            return idleTimeoutSeconds;
        }

        public void setIdleTimeoutSeconds(int idleTimeoutSeconds) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
        }
    }

    public static class Sync {

        /** 单批拉取行数 */
        private int batchSize = 2000;

        /** 同步执行线程池大小 */
        private int executorParallelism = 2;

        /** 每租户同步并发上限（防噪声邻居：满则本轮调度跳过，下轮扫描自愈） */
        private int tenantConcurrency = 2;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getExecutorParallelism() {
            return executorParallelism;
        }

        public void setExecutorParallelism(int executorParallelism) {
            this.executorParallelism = executorParallelism;
        }

        public int getTenantConcurrency() {
            return tenantConcurrency;
        }

        public void setTenantConcurrency(int tenantConcurrency) {
            this.tenantConcurrency = tenantConcurrency;
        }
    }

    public static class Open {

        /** 签名时间戳允许偏移秒数（防重放窗口） */
        private int signatureTimeoutSeconds = 300;

        /** API 未配置 qps_limit 时的默认每分钟调用上限 */
        private int defaultPerMinute = 60;

        public int getSignatureTimeoutSeconds() {
            return signatureTimeoutSeconds;
        }

        public void setSignatureTimeoutSeconds(int signatureTimeoutSeconds) {
            this.signatureTimeoutSeconds = signatureTimeoutSeconds;
        }

        public int getDefaultPerMinute() {
            return defaultPerMinute;
        }

        public void setDefaultPerMinute(int defaultPerMinute) {
            this.defaultPerMinute = defaultPerMinute;
        }
    }

    public String getCryptKey() {
        return cryptKey;
    }

    public void setCryptKey(String cryptKey) {
        this.cryptKey = cryptKey;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    public Sync getSync() {
        return sync;
    }

    public void setSync(Sync sync) {
        this.sync = sync;
    }

    public Open getOpen() {
        return open;
    }

    public void setOpen(Open open) {
        this.open = open;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    public static class Service {

        /** API 发布是否需要审批（false = 保存即发布） */
        private boolean approvalEnabled = true;

        public boolean isApprovalEnabled() {
            return approvalEnabled;
        }

        public void setApprovalEnabled(boolean approvalEnabled) {
            this.approvalEnabled = approvalEnabled;
        }
    }
}
