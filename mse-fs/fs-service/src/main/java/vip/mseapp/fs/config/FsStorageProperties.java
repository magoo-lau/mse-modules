package vip.mseapp.fs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置（mse.fs.storage.*）。
 *
 * <p>{@code type} 决定存储实现：{@code local}（缺省，本地文件系统）/ {@code rustfs}
 * （S3 兼容对象存储，需 classpath 存在 AWS SDK）。预签名直链仅 rustfs 模式且
 * {@code presign.enabled=true} 时可用。
 */
@Component
@ConfigurationProperties(prefix = "mse.fs.storage")
public class FsStorageProperties {

    /** 存储实现：local（缺省）| rustfs */
    private String type = "local";

    private final Local local = new Local();
    private final Rustfs rustfs = new Rustfs();
    private final Presign presign = new Presign();

    public static class Local {

        /** 本地存储根目录（按 {tenantId}/ 子目录隔离租户） */
        private String baseDir = "./fs-files";

        public String getBaseDir() {
            return baseDir;
        }

        public void setBaseDir(String baseDir) {
            this.baseDir = baseDir;
        }
    }

    public static class Rustfs {

        /** RustFS S3 兼容端点（如 http://localhost:9000） */
        private String endpoint = "http://localhost:9000";

        /** 访问密钥（对应 RUSTFS_ACCESS_KEY） */
        private String accessKey = "rustfsadmin";

        /** 私有密钥（对应 RUSTFS_SECRET_KEY） */
        private String secretKey = "rustfsadmin";

        /** 桶名（不存在时启动自动创建；桶内按 {tenantId}/ 前缀隔离租户） */
        private String bucket = "mse-fs";

        /** S3 region（S3 兼容存储通常任意值即可，如 us-east-1） */
        private String region = "us-east-1";

        /** path-style 寻址（RustFS/MinIO 类单机部署必须 true） */
        private boolean pathStyleAccess = true;

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }
    }

    public static class Presign {

        /** 预签名直链开关（仅 rustfs 模式生效；默认关闭，下载统一走服务端代理以保留鉴权与审计） */
        private boolean enabled = false;

        /** 预签名有效期（秒） */
        private long ttlSeconds = 3600;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Local getLocal() {
        return local;
    }

    public Rustfs getRustfs() {
        return rustfs;
    }

    public Presign getPresign() {
        return presign;
    }
}
