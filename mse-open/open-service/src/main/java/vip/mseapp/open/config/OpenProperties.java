package vip.mseapp.open.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 开放平台配置（mse.open.*）。
 */
@Component
@ConfigurationProperties(prefix = "mse.open")
public class OpenProperties {

    /** AppSecret AES-GCM 落库加密密钥（生产必须修改；更换后历史密文不可解，需重置应用密钥） */
    private String cryptKey = "mse-open-default-crypt-key-please-change";

    /** 应用 access_token 有效期（分钟） */
    private long tokenTtlMinutes = 120;

    /** 应用未配置限流时的默认 QPS */
    private long defaultRateLimit = 50;

    /** 泛化分发目标接口的包名白名单（目录误配内部接口时拒绝分发） */
    private List<String> rpcAllowedPackages = List.of("vip.mseapp.");

    /** 泛化调用超时毫秒（dubbo 模式） */
    private long rpcTimeoutMillis = 30000;

    /** 请求签名时间戳允许窗口（秒，± 双向；nonce 去重 TTL 与其同源） */
    private long signTimestampWindowSeconds = 300;

    public long getSignTimestampWindowSeconds() {
        return signTimestampWindowSeconds;
    }

    public void setSignTimestampWindowSeconds(long signTimestampWindowSeconds) {
        this.signTimestampWindowSeconds = signTimestampWindowSeconds;
    }

    public String getCryptKey() {
        return cryptKey;
    }

    public void setCryptKey(String cryptKey) {
        this.cryptKey = cryptKey;
    }

    public long getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    public void setTokenTtlMinutes(long tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    public long getDefaultRateLimit() {
        return defaultRateLimit;
    }

    public void setDefaultRateLimit(long defaultRateLimit) {
        this.defaultRateLimit = defaultRateLimit;
    }

    public List<String> getRpcAllowedPackages() {
        return rpcAllowedPackages;
    }

    public void setRpcAllowedPackages(List<String> rpcAllowedPackages) {
        this.rpcAllowedPackages = rpcAllowedPackages;
    }

    public long getRpcTimeoutMillis() {
        return rpcTimeoutMillis;
    }

    public void setRpcTimeoutMillis(long rpcTimeoutMillis) {
        this.rpcTimeoutMillis = rpcTimeoutMillis;
    }
}
