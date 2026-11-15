package vip.mseapp.open.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.open.config.OpenCrypt;
import vip.mseapp.open.config.OpenProperties;
import vip.mseapp.open.entity.OpenApp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 请求签名验签（二期外部接入标准，sign_required=1 的应用强制）。
 *
 * <p>请求头：{@code X-Open-Timestamp}（毫秒时间戳）、{@code X-Open-Nonce}（一次性随机串）、
 * {@code X-Open-Signature}（HMAC-SHA256 十六进制小写）。签名原文以 \n 连接：
 * {@code appKey、method、path[?query]、timestamp、nonce、sha256Hex(body)}，密钥为 AppSecret 明文。
 * 时间戳窗口 ±{@code mse.open.sign-timestamp-window-seconds}（默认 300s）；
 * nonce 经 Redis SETNX 去重（TTL = 窗口 + 余量，多实例共享防重放）。
 * 任何失败返回失败原因，由拦截器统一 401 应答并记审计。
 */
@Service
public class OpenSignatureService {

    public static final String HEADER_TIMESTAMP = "X-Open-Timestamp";
    public static final String HEADER_NONCE = "X-Open-Nonce";
    public static final String HEADER_SIGNATURE = "X-Open-Signature";

    private final StringRedisTemplate redis;
    private final OpenCrypt crypt;
    private final OpenProperties properties;

    public OpenSignatureService(StringRedisTemplate redis, OpenCrypt crypt, OpenProperties properties) {
        this.redis = redis;
        this.crypt = crypt;
        this.properties = properties;
    }

    /**
     * 验签。
     *
     * @param body 请求体字节（缓存过滤器提供，可能为 null = 无请求体）
     * @return null = 通过；非 null = 失败原因
     */
    public String verify(OpenApp app, HttpServletRequest request, byte[] body) {
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            return "缺少签名请求头（X-Open-Timestamp / X-Open-Nonce / X-Open-Signature）";
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return "时间戳不合法";
        }
        long window = properties.getSignTimestampWindowSeconds();
        if (Math.abs(System.currentTimeMillis() - ts) > window * 1000L) {
            return "时间戳超出窗口（±" + window + "s），请校准调用方时钟";
        }
        String trimmedNonce = nonce.trim();
        if (trimmedNonce.length() > 128) {
            return "nonce 过长（≤128 字符）";
        }
        Boolean first = redis.opsForValue().setIfAbsent(
                "open:app:nonce:" + app.getId() + ":" + trimmedNonce, "1", Duration.ofSeconds(window + 60));
        if (!Boolean.TRUE.equals(first)) {
            return "nonce 已使用（疑似重放请求）";
        }
        String expected = hmacSha256(secret(app), canonical(app, request, timestamp.trim(), trimmedNonce, body));
        if (expected == null) {
            return "验签计算失败";
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))
                ? null : "签名不匹配";
    }

    /** 签名原文（\n 连接）：appKey / method / path[?query] / timestamp / nonce / sha256Hex(body) */
    public static String canonical(OpenApp app, HttpServletRequest request,
                                   String timestamp, String nonce, byte[] body) {
        String query = request.getQueryString();
        String path = request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query);
        return String.join("\n",
                app.getAppKey(),
                request.getMethod().toUpperCase(),
                path,
                timestamp,
                nonce,
                sha256Hex(body));
    }

    /** body 的 SHA-256 十六进制（null 视为空字节，与「无请求体」同口径） */
    public static String sha256Hex(byte[] body) {
        try {
            byte[] data = body == null ? new byte[0] : body;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String hmacSha256(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    private String secret(OpenApp app) {
        String secret = crypt.decrypt(app.getAppSecret());
        return secret == null ? "" : secret;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
