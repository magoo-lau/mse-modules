package vip.mseapp.im.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImWorkbenchItem;
import vip.mseapp.im.mapper.ImWorkbenchItemMapper;
import vip.mseapp.open.api.OpenAppCheck;
import vip.mseapp.im.support.WbSec;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 工作台外部应用免登授权（OAuth2 授权码模式，微信接入第三方同款）：
 * 用户点宫格 → 跳转外部系统时 URL 带一次性授权码（{@code ImWorkbenchService.jump} 签发，
 * 入口域名已按应用域名白名单校验）→ 对方服务端用 app_key + app_secret + code 兑换
 * access_token → 后续凭 Bearer token 调 userinfo 识别用户身份。
 *
 * <p>安全模型：code 一次性（GETDEL 原子取删）+ 短时效（默认 300s）+ 绑定签发者与应用；
 * 凭证校验归口开放平台（RPC checkCredential：常量时间比对 + 应用停用/过期/删除即时失效），
 * 入口行仅存绑定的 app_key 冗余列（授权码签发时绑定应用，兑换时回查校验一致）；
 * access_token 存 Redis（默认 2h），应用侧换发密钥不影响已签发 token（到期自然失效）。
 */
@Service
public class ImOauthService {

    private static final String AUTH_CODE_KEY_PREFIX = "im:wb:authcode:";
    private static final String TOKEN_KEY_PREFIX = "im:wb:token:";

    private final ImWorkbenchItemMapper itemMapper;
    private final StringRedisTemplate redis;
    private final MseImProperties properties;
    private final ObjectProvider<OpenPlatformGateway> openAppGateway;

    public ImOauthService(ImWorkbenchItemMapper itemMapper, StringRedisTemplate redis,
                          MseImProperties properties, ObjectProvider<OpenPlatformGateway> openAppGateway) {
        this.itemMapper = itemMapper;
        this.redis = redis;
        this.properties = properties;
        this.openAppGateway = openAppGateway;
    }

    /** 授权码兑换 access_token：code 无论成败均已被取删（一次性） */
    public TokenVO token(String appKey, String appSecret, String code) {
        if (isBlank(appKey) || isBlank(appSecret) || isBlank(code)) {
            throw new ServiceException(400, "app_key/app_secret/code 均不能为空");
        }
        byte[] raw = redis.execute((RedisCallback<byte[]>) conn ->
                conn.stringCommands().getDel((AUTH_CODE_KEY_PREFIX + code).getBytes(StandardCharsets.UTF_8)));
        if (raw == null) {
            throw new ServiceException(400, "授权码无效或已使用");
        }
        ImWorkbenchService.AuthCodePayload payload;
        try {
            payload = WbSec.JSON.readValue(new String(raw, StandardCharsets.UTF_8),
                    ImWorkbenchService.AuthCodePayload.class);
        } catch (Exception e) {
            throw new ServiceException(400, "授权码无效");
        }
        if (!WbSec.constantEquals(payload.appKey(), appKey)) {
            throw new ServiceException(400, "授权码与应用不匹配");
        }
        // 授权码签发时已绑定租户世界：回查入口行校验绑定关系与状态（独立库租户经 runAs 路由）
        ImWorkbenchItem item = TenantContext.runAs(payload.tenantId(),
                () -> itemMapper.selectById(payload.itemId()));
        if (item == null || !payload.tenantId().equals(item.getTenantId())) {
            throw new ServiceException(400, "授权码对应的入口不存在或已删除");
        }
        if (item.getStatus() != null && item.getStatus() != 0) {
            throw new ServiceException(400, "该工作台入口已停用");
        }
        if (!WbSec.constantEquals(item.getAppKey(), payload.appKey())) {
            throw new ServiceException(400, "授权码与应用不匹配");
        }
        // 凭证校验归口开放平台：app_key + app_secret 常量时间比对，应用停用/过期/删除即时失效
        OpenPlatformGateway gateway = openAppGateway.getIfAvailable();
        if (gateway == null) {
            throw new ServiceException(503, "应用凭证校验服务不可用（开放平台未接入）");
        }
        OpenAppCheck check = gateway.checkCredential(payload.appKey(), appSecret);
        if (check == null || !check.isValid()) {
            throw new ServiceException(400, "应用凭证不正确"
                    + (check != null && check.getReason() != null ? "（" + check.getReason() + "）" : ""));
        }
        String token = WbSec.newToken();
        int ttl = properties.getWorkbench().getAccessTokenTtlSeconds();
        redis.opsForValue().set(TOKEN_KEY_PREFIX + token,
                WbSec.JSON.writeValueAsString(new TokenPayload(payload.tenantId(), payload.userId(),
                        payload.username(), payload.nickname(), payload.appKey())),
                Duration.ofSeconds(ttl));
        return new TokenVO(token, "Bearer", ttl,
                new UserVO(payload.tenantId(), payload.userId(), payload.username(), payload.nickname()));
    }

    /** 凭 Bearer access_token 识别用户身份（token 有效期内可复用） */
    public UserVO userinfo(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ServiceException(401, "缺少 Bearer access_token");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            throw new ServiceException(401, "access_token 不能为空");
        }
        String raw = redis.opsForValue().get(TOKEN_KEY_PREFIX + token);
        if (raw == null) {
            throw new ServiceException(401, "access_token 无效或已过期");
        }
        TokenPayload payload;
        try {
            payload = WbSec.JSON.readValue(raw, TokenPayload.class);
        } catch (Exception e) {
            throw new ServiceException(401, "access_token 无效");
        }
        return new UserVO(payload.tenantId(), payload.userId(), payload.username(), payload.nickname());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 兑换结果（OAuth2 惯例字段 + user 身份） */
    public record TokenVO(String accessToken, String tokenType, int expiresIn, UserVO user) {
    }

    /** 用户身份（第三方识别用户的最小集合） */
    public record UserVO(Long tenantId, Long userId, String username, String nickname) {
    }

    /** token 绑定的身份与应用（Jackson record，存 Redis） */
    record TokenPayload(Long tenantId, Long userId, String username, String nickname, String appKey) {
    }
}
