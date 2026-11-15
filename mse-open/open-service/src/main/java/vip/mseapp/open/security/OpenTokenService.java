package vip.mseapp.open.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.open.api.OpenAppCheck;
import vip.mseapp.open.config.OpenCrypt;
import vip.mseapp.open.config.OpenProperties;
import vip.mseapp.open.entity.OpenApp;
import vip.mseapp.open.mapper.OpenAppMapper;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 应用 access_token 签发与校验（独立于 Sa-Token 人机会话的机器凭证体系）。
 *
 * <p>token 存 Redis（open:app:token:{token} → OpenAppSession JSON，TTL 可配），
 * 应用停用/删除即时生效——拦截器每次请求回查 open_app 实时状态（按会话快照租户路由到应用所属世界）。
 * AppSecret 校验用常数时间比较，防时序侧信道。
 *
 * <p>凭证世界化（标识符自带世界段）：appKey 格式 {@code mse-{tenantId}-{hex}}——
 * 换凭证通道无租户上下文，解析 appKey 世界段后 {@link TenantContext#runAs} 切到应用所属世界
 * （一切皆租户：独立库租户专属库 / 共享租户绑定库 / 平台世界 0 平台库）读取校验；
 * 旧格式 appKey（24 位十六进制，无世界段）主库 runIgnore 兜底（过渡期 deprecated，
 * 独立库租户旧凭证请重置密钥/重建应用换发）。
 */
@Service
public class OpenTokenService {

    private static final String TOKEN_KEY_PREFIX = "open:app:token:";

    private final OpenAppMapper appMapper;
    private final OpenCrypt crypt;
    private final OpenProperties properties;
    private final StringRedisTemplate redis;
    private final JsonMapper objectMapper;

    public OpenTokenService(OpenAppMapper appMapper, OpenCrypt crypt, OpenProperties properties,
                            StringRedisTemplate redis, JsonMapper objectMapper) {
        this.appMapper = appMapper;
        this.crypt = crypt;
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 签发结果 */
    public record TokenIssue(String accessToken, long expiresIn, String appKey, String appName) {
    }

    /**
     * 凭 app_key + app_secret 换取 access_token。
     *
     * <p>凭证错误、应用停用、凭证过期、来源域名不在白名单一律 401（不区分具体原因，避免向探测方泄露细节）。
     * 域名白名单约束浏览器侧来源（Origin/Referer 头）：已配置但请求无来源头（纯服务端调用）同样拒绝。
     */
    public TokenIssue issue(String appKey, String appSecret, jakarta.servlet.http.HttpServletRequest request) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new ServiceException(401, "缺少 app_key 或 app_secret");
        }
        OpenApp app = findByAppKey(appKey);
        if (app == null) {
            throw new ServiceException(401, "app_key 或 app_secret 错误");
        }
        verifySecret(app, appSecret);
        if (app.getStatus() != null && app.getStatus() != OpenApp.STATUS_ENABLED) {
            throw new ServiceException(401, "应用已停用");
        }
        if (app.getExpireTime() != null && app.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(401, "应用凭证已过期");
        }
        // 域名白名单：凭证校验通过后再拦（与拦截器同口径，Origin/Referer；无头 = 不命中）
        if (!OpenDomainWhitelist.allowed(app.getDomainWhitelist(), OpenDomainWhitelist.originHost(request))) {
            throw new ServiceException(401, "app_key 或 app_secret 错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        OpenAppSession session = new OpenAppSession(app.getId(), app.getAppKey(), app.getTenantId());
        try {
            redis.opsForValue().set(TOKEN_KEY_PREFIX + token,
                    objectMapper.writeValueAsString(session),
                    Duration.ofMinutes(properties.getTokenTtlMinutes()));
        } catch (Exception e) {
            throw new ServiceException(500, "访问凭证写入失败：" + e.getMessage());
        }
        return new TokenIssue(token, properties.getTokenTtlMinutes() * 60, app.getAppKey(), app.getName());
    }

    /** 解析 token 为会话；无效/过期返回 empty（不抛错，供拦截器统一处置） */
    public Optional<OpenAppSession> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(TOKEN_KEY_PREFIX + token);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, OpenAppSession.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 吊销 token（登出/凭证泄露应急处置） */
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redis.delete(TOKEN_KEY_PREFIX + token);
        }
    }

    /**
     * 应用凭证综合校验（RPC 契约 {@code RemoteOpenAppService#checkCredential} 消费，
     * 业务积木兑换自有 token 前调用）：常量时间比对 + 状态/过期检查，不含域名白名单。
     * 失败原因措辞已做防探测模糊（与 {@link #issue} 同口径），可直接透出。
     */
    public OpenAppCheck checkCredential(String appKey, String appSecret) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            return OpenAppCheck.invalid("缺少 app_key 或 app_secret");
        }
        OpenApp app = findByAppKey(appKey);
        if (app == null || !secretMatches(app, appSecret)) {
            return OpenAppCheck.invalid("app_key 或 app_secret 错误");
        }
        if (app.getStatus() != null && app.getStatus() != OpenApp.STATUS_ENABLED) {
            return OpenAppCheck.invalid("应用已停用");
        }
        if (app.getExpireTime() != null && app.getExpireTime().isBefore(LocalDateTime.now())) {
            return OpenAppCheck.invalid("应用凭证已过期");
        }
        return OpenAppCheck.ok(app.getId(), app.getTenantId());
    }

    /**
     * 域名白名单校验（RPC 契约 {@code RemoteOpenAppService#domainAllowed} 消费，
     * code 授权模式签发授权码前的回调域安全检查）：应用不存在返回 false。
     */
    public boolean domainAllowed(String appKey, String host) {
        if (appKey == null || appKey.isBlank()) {
            return false;
        }
        OpenApp app = findByAppKey(appKey);
        return app != null && OpenDomainWhitelist.allowed(app.getDomainWhitelist(), host);
    }

    /** 按 appKey 定位应用行：世界段解析切库（一切皆租户）；旧格式（无世界段）主库兜底 */
    private OpenApp findByAppKey(String appKey) {
        String key = appKey.trim();
        Long world = parseWorld(key);
        if (world != null) {
            return TenantContext.runAs(world, () -> appMapper.selectOne(
                    new LambdaQueryWrapper<OpenApp>().eq(OpenApp::getAppKey, key)));
        }
        return TenantContext.runIgnore(() -> appMapper.selectOne(
                new LambdaQueryWrapper<OpenApp>().eq(OpenApp::getAppKey, key)));
    }

    /** 解析 appKey 世界段（mse-{tenantId}-{hex}）；旧格式（24 位十六进制，无世界段）返回 null */
    static Long parseWorld(String appKey) {
        if (appKey == null || !appKey.startsWith("mse-")) {
            return null;
        }
        String rest = appKey.substring(4);
        int split = rest.indexOf('-');
        if (split <= 0) {
            return null;
        }
        try {
            return Long.valueOf(rest.substring(0, split));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** AppSecret 常量时间比对（AES-GCM 解密后 memcmp，防时序侧信道） */
    private boolean secretMatches(OpenApp app, String appSecret) {
        String plain;
        try {
            plain = crypt.decrypt(app.getAppSecret());
        } catch (ServiceException e) {
            return false;
        }
        return MessageDigest.isEqual(
                plain.getBytes(StandardCharsets.UTF_8),
                appSecret.getBytes(StandardCharsets.UTF_8));
    }

    private void verifySecret(OpenApp app, String appSecret) {
        if (!secretMatches(app, appSecret)) {
            throw new ServiceException(401, "app_key 或 app_secret 错误");
        }
    }
}
