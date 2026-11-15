package vip.mseapp.im.service;

import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.security.stp.StpTenantUtil;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 工作台 SSO 一次性 code：聊天客户端持登录态换取短时 code，Web 端用 code 兑换标准 Sa-Token 会话。
 *
 * <p>安全模型：code 一次性（GETDEL 原子取删）+ 短有效期 + 绑定签发者与目标站点 Origin
 * （兑换请求的 Origin 必须与 code 签发时 targetUrl 的 Origin 一致，防 code 投递到其他站点），
 * 兑换产物为标准租户端会话（terminal extra 标记来源，可在登录设备页自助下线）。
 */
@Service
public class ImSsoCodeService {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_KEY_PREFIX = "im:sso:code:";
    private static final String RATE_LIMIT_KEY_PREFIX = "im:sso:rl:";
    private static final long RATE_LIMIT_WINDOW_SECONDS = 60;

    private final MseImProperties properties;
    private final StringRedisTemplate redis;

    public ImSsoCodeService(MseImProperties properties, StringRedisTemplate redis) {
        this.properties = properties;
        this.redis = redis;
    }

    /** 签发：targetUrl 为工作台跳转的完整 mse 地址，返回追加 ssoCode 参数后的 URL */
    public SsoCodeVO issue(String targetUrl) {
        Long tenantId = StpTenantUtil.getTenantIdFromLoginId();
        Long userId = StpTenantUtil.getUserIdFromLoginId();
        URI uri = parseTarget(targetUrl);
        rateLimit(tenantId, userId);
        String code = newCode();
        SsoPayload payload = new SsoPayload(tenantId, userId, StpTenantUtil.getUsername(), nickname(), origin(uri));
        redis.opsForValue().set(CODE_KEY_PREFIX + code, MAPPER.writeValueAsString(payload),
                Duration.ofSeconds(properties.getSso().getCodeTtlSeconds()));
        String separator = targetUrl.contains("?") ? "&" : "?";
        return new SsoCodeVO(targetUrl + separator + "ssoCode=" + code, properties.getSso().getCodeTtlSeconds());
    }

    /** 兑换：校验一次性 + Origin 一致后签发标准租户会话；code 无论成败均已被取删 */
    public LoginVO exchange(String code, String requestOrigin) {
        byte[] raw = redis.execute((RedisCallback<byte[]>) conn ->
                conn.stringCommands().getDel((CODE_KEY_PREFIX + code).getBytes(StandardCharsets.UTF_8)));
        if (raw == null) {
            throw new ServiceException(400, "兑换码无效或已使用");
        }
        SsoPayload payload;
        try {
            payload = MAPPER.readValue(new String(raw, StandardCharsets.UTF_8), SsoPayload.class);
        } catch (Exception e) {
            throw new ServiceException(400, "兑换码无效");
        }
        verifyOrigin(payload, requestOrigin);
        SaLoginParameter parameter = new SaLoginParameter()
                .setDeviceType("pc")
                .setTerminalExtra("source", "im-sso");
        StpTenantUtil.login(payload.tenantId(), payload.userId(), payload.username(), payload.nickname(), parameter);
        return new LoginVO(MseConstants.TOKEN_NAME, StpTenantUtil.stpLogic.getTokenValue(),
                payload.tenantId(), payload.userId(), payload.nickname(), false);
    }

    /** 同用户签发限频：60 秒窗口内超过上限即拒绝（Redis INCR + 首次过期） */
    private void rateLimit(Long tenantId, Long userId) {
        String rateLimitKey = RATE_LIMIT_KEY_PREFIX + tenantId + "-" + userId;
        Long count = redis.opsForValue().increment(rateLimitKey);
        if (count != null && count == 1) {
            redis.expire(rateLimitKey, Duration.ofSeconds(RATE_LIMIT_WINDOW_SECONDS));
        }
        if (count != null && count > properties.getSso().getIssueRateLimit()) {
            throw new ServiceException(429, "兑换码签发过于频繁，请稍后再试");
        }
    }

    private void verifyOrigin(SsoPayload payload, String requestOrigin) {
        if (requestOrigin != null && !requestOrigin.isBlank()) {
            if (!normalize(requestOrigin).equalsIgnoreCase(payload.origin())) {
                throw new ServiceException(403, "兑换码与当前访问站点不匹配");
            }
            return;
        }
        if (!properties.getSso().isAllowEmptyOrigin()) {
            throw new ServiceException(400, "缺少 Origin 请求头，拒绝兑换");
        }
    }

    private static URI parseTarget(String targetUrl) {
        URI uri;
        try {
            uri = URI.create(targetUrl);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(400, "targetUrl 非法");
        }
        if ((!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()) {
            throw new ServiceException(400, "targetUrl 必须为绝对 http(s) 地址");
        }
        return uri;
    }

    /** Origin 归一：小写 + 去尾斜杠（scheme://authority 原样保留端口写法） */
    private static String origin(URI uri) {
        return normalize(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    private static String normalize(String origin) {
        String value = origin.trim().toLowerCase();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String newCode() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String nickname() {
        Object nickname = StpTenantUtil.getSession().get(StpTenantUtil.SESSION_NICKNAME);
        return nickname == null ? null : nickname.toString();
    }

    /** code 绑定的签发者与目标站点（Jackson record） */
    record SsoPayload(Long tenantId, Long userId, String username, String nickname, String origin) {
    }

    /** 签发结果 */
    public record SsoCodeVO(String url, int expiresIn) {
    }

    /** 兑换结果（字段与基座 /auth/login 的 LoginResult 对齐，前端 setLogin 直接消费） */
    public record LoginVO(String tokenName, String tokenValue, Long tenantId, Long userId,
                          String nickname, boolean platform) {
    }
}
