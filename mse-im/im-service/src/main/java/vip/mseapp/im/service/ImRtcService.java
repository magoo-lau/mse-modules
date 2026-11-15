package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImInstanceConfig;
import vip.mseapp.im.mapper.ImInstanceConfigMapper;
import vip.mseapp.security.stp.StpTenantUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 音视频通话 RTC 服务（LiveKit）：会话下发 rtc 坐标 + 房间 token 签发。
 *
 * <p>LiveKit Access Token 为标准 JWT（HS256）：iss=apiKey、sub=identity（用户），
 * video grant = roomJoin + 指定房间（房间名 = 通话 callId，双端各签各的 token 进同一房间）。
 * 手写编码零新依赖（Java 21 HMAC）。
 */
@Service
public class ImRtcService {

    private final MseImProperties properties;
    private final ImInstanceConfigMapper instanceMapper;

    public ImRtcService(MseImProperties properties, ImInstanceConfigMapper instanceMapper) {
        this.properties = properties;
        this.instanceMapper = instanceMapper;
    }

    /** 客户端会话下发的 RTC 坐标：enabled=false 客户端隐藏通话入口 */
    public RtcVO rtcForSession(Long tenantId) {
        MseImProperties.Livekit livekit = properties.getLivekit();
        boolean enabled = livekit.isEnabled() && !isBlank(livekitUrl(tenantId))
                && !isBlank(livekit.getApiKey()) && !isBlank(livekit.getApiSecret());
        return new RtcVO(enabled, livekitUrl(tenantId));
    }

    /**
     * 签发 LiveKit 房间 token（登录态用户以本人身份进指定房间）。
     *
     * @param room 房间名（= 通话 callId，双方各自调本端点传同一 room）
     */
    public TokenVO issueToken(String room) {
        MseImProperties.Livekit livekit = properties.getLivekit();
        if (!livekit.isEnabled()) {
            throw new ServiceException(503, "音视频通话未开通（mse.im.livekit.enabled）");
        }
        if (isBlank(livekit.getApiKey()) || isBlank(livekit.getApiSecret())) {
            throw new ServiceException(503, "LiveKit 凭据未配置（mse.im.livekit.api-key/api-secret）");
        }
        if (room == null || room.isBlank() || room.length() > 128 || !room.matches("[A-Za-z0-9_-]+")) {
            throw new ServiceException(400, "非法房间名");
        }
        Long tenantId = StpTenantUtil.getTenantIdFromLoginId();
        Long userId = StpTenantUtil.getUserIdFromLoginId();
        String identity = String.valueOf(userId);
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("room", room);
        video.put("roomJoin", true);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", true);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", livekit.getApiKey());
        claims.put("sub", identity);
        claims.put("jti", identity);
        claims.put("nbf", now - 10);
        claims.put("exp", now + Math.max(60, livekit.getTokenTtlSeconds()));
        claims.put("video", video);
        String token = signJwt(claims, livekit.getApiSecret());
        return new TokenVO(token, livekitUrl(tenantId));
    }

    /** 每租户 LiveKit 信令地址：台账行 livekit_url 优先（开通编排写入），回落全局配置 */
    private String livekitUrl(Long tenantId) {
        if (tenantId != null && tenantId > 0) {
            ImInstanceConfig row = instanceMapper.selectOne(new LambdaQueryWrapper<ImInstanceConfig>()
                    .eq(ImInstanceConfig::getTenantId, tenantId)
                    .last("limit 1"));
            if (row != null && !isBlank(row.getLivekitUrl())) {
                return row.getLivekitUrl();
            }
        }
        return properties.getLivekit().getUrl();
    }

    /** 手写 HS256 JWT：base64url(header).base64url(payload).base64url(HMAC-SHA256) */
    private static String signJwt(Map<String, Object> claims, String secret) {
        try {
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            String header = b64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = b64.encodeToString(new tools.jackson.databind.json.JsonMapper()
                    .writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = b64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new ServiceException(500, "RTC token 签发失败：" + e.getMessage());
        }
    }

    /** 当前租户（通话 token 端点用） */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 会话响应内嵌的 RTC 坐标 */
    public record RtcVO(boolean enabled, String url) {
    }

    /** token 签发响应 */
    public record TokenVO(String token, String url) {
    }
}
