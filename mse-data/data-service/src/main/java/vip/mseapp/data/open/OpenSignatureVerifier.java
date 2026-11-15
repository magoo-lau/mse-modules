package vip.mseapp.data.open;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.config.DataProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

/**
 * 开放 API 签名校验：HMAC-SHA256 + 时间戳窗口 + nonce 防重放（Redis SETNX）。
 *
 * <p>签名串：{@code appKey + "\n" + timestamp + "\n" + nonce}，密钥为 appSecret 明文。
 */
@Component
public class OpenSignatureVerifier {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final RedisTemplate<String, Object> redisTemplate;
    private final DataProperties properties;

    public OpenSignatureVerifier(RedisTemplate<String, Object> redisTemplate,
                                 DataProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** 校验通过即返回，否则抛 401；nonce 首次出现即占位（窗口内不可复用） */
    public void verify(String appKey, String secret, String timestamp, String nonce, String sign) {
        int window = properties.getOpen().getSignatureTimeoutSeconds();
        long ts;
        try {
            ts = Long.parseLong(timestamp == null ? "" : timestamp.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(401, "签名时间戳非法");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - ts);
        if (skew > window) {
            throw new ServiceException(401, "签名已过期（时间戳偏移超 " + window + " 秒）");
        }
        if (nonce == null || nonce.isBlank() || nonce.length() > 64) {
            throw new ServiceException(401, "签名 nonce 非法");
        }
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent("mse:data:open:nonce:" + appKey + ":" + nonce, "1",
                        Duration.ofSeconds(window * 2L));
        if (!Boolean.TRUE.equals(first)) {
            throw new ServiceException(401, "签名 nonce 已被使用");
        }
        String expected = hmacSha256Hex(secret, appKey + "\n" + timestamp + "\n" + nonce);
        if (!constantTimeEquals(expected, sign == null ? "" : sign.toLowerCase())) {
            throw new ServiceException(401, "签名校验失败");
        }
    }

    public String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new ServiceException(500, "签名计算失败：" + e.getMessage());
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
