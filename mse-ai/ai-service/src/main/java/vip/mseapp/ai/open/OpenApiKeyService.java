package vip.mseapp.ai.open;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.entity.AiApiKey;
import vip.mseapp.ai.mapper.AiApiKeyMapper;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 开放 API 密钥服务：签发（明文仅返回一次）/ 校验（SHA-256 哈希比对）/ Redis 每分钟限流。
 *
 * <p>凭证世界化（标识符自带世界段，同 mse-im 回调 / mse-fs 素材范式）：appId 格式
 * {@code ai-{tenantId}-{hex}}——verify 发生在租户上下文建立之前，解析 appId 世界段后
 * {@link TenantContext#runAs} 切到密钥所属世界（一切皆租户：独立库租户专属库 / 共享租户绑定库 /
 * 平台世界 0 平台库）读取校验；旧格式 appId（无世界段）主库 {@code runIgnore} 兜底扫描
 * （过渡期 deprecated，独立库租户旧 Key 请换发）。管理侧读写同随世界路由 + 显式归属校验。
 * 鉴权成功后由 {@code OpenApiAuthFilter} 按密钥绑定租户上下文；限流计数为无上下文线程（纯 Redis 无 SQL）。
 */
@Service
public class OpenApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiKeyService.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 签发结果：明文 key 只在创建响应中出现一次 */
    public record IssuedKey(AiApiKey entity, String apiKey) {
    }

    private final AiApiKeyMapper keyMapper;
    private final ObjectProvider<StringRedisTemplate> redisTemplate;

    public OpenApiKeyService(AiApiKeyMapper keyMapper, ObjectProvider<StringRedisTemplate> redisTemplate) {
        this.keyMapper = keyMapper;
        this.redisTemplate = redisTemplate;
    }

    public IssuedKey create(Long tenantId, String remark, String agentCodes, Integer ratePerMinute) {
        // appId 自带世界段：无上下文的 verify 侧凭 appId 即可定位密钥所属世界（世界内唯一即可）
        String appId = "ai-" + tenantId + "-" + randomHex(8);
        String apiKey = "mk-" + randomHex(24);
        AiApiKey entity = new AiApiKey();
        entity.setAppId(appId);
        entity.setKeyHash(sha256(apiKey));
        entity.setKeyPrefix(apiKey.substring(0, 11));
        entity.setTenantId(tenantId);
        entity.setAgentCodes(agentCodes);
        entity.setRatePerMinute(ratePerMinute);
        entity.setStatus(0);
        entity.setRemark(remark);
        TenantContext.runAs(tenantId, () -> keyMapper.insert(entity));
        return new IssuedKey(entity, apiKey);
    }

    /** 分页：tenantId 非空时按归属过滤（本世界路由读取）；空 = 平台全局视图（主库全局扫描，不含独立库租户专属库 Key） */
    public Page<AiApiKey> page(long pageNum, long pageSize, Long tenantId) {
        if (tenantId != null) {
            return TenantContext.runAs(tenantId, () -> {
                LambdaQueryWrapper<AiApiKey> wrapper = new LambdaQueryWrapper<AiApiKey>()
                        .eq(AiApiKey::getTenantId, tenantId)
                        .orderByDesc(AiApiKey::getId);
                return keyMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
            });
        }
        return TenantContext.runIgnore(() -> keyMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<AiApiKey>().orderByDesc(AiApiKey::getId)));
    }

    public void updateStatus(Long id, int status, Long tenantId) {
        TenantContext.runAs(tenantId, () -> {
            AiApiKey key = requireOwnedKey(id, tenantId);
            key.setStatus(status);
            keyMapper.updateById(key);
        });
    }

    public void delete(Long id, Long tenantId) {
        TenantContext.runAs(tenantId, () -> {
            requireOwnedKey(id, tenantId);
            keyMapper.deleteById(id);
        });
    }

    /** 本世界自有 Key（归属等值校验兜底，防跨租户操作）；须在 runAs 世界内调用 */
    private AiApiKey requireOwnedKey(Long id, Long tenantId) {
        AiApiKey key = keyMapper.selectById(id);
        if (key == null || tenantId == null || !tenantId.equals(key.getTenantId())) {
            throw new ServiceException(404, "API Key 不存在");
        }
        return key;
    }

    /** 过滤器鉴权入口：appId + key 明文 → 密钥实体（失败抛 401 语义异常；租户上下文建立前调用） */
    public AiApiKey verify(String appId, String apiKey) {
        Long world = parseWorld(appId);
        AiApiKey entity;
        if (world != null) {
            // appId 自带世界段：切到密钥所属世界读取（行级注入对共享租户生效，独立库租户落专属库）
            entity = TenantContext.runAs(world, () -> keyMapper.selectOne(new LambdaQueryWrapper<AiApiKey>()
                    .eq(AiApiKey::getAppId, appId).last("LIMIT 1")));
        } else {
            // 旧格式 appId（无世界段）：主库兜底扫描（deprecated，独立库租户旧 Key 请换发）
            log.debug("[mse-ai] 开放 API 凭证命中旧格式 appId（无世界段，主库兜底）: {}", appId);
            entity = TenantContext.runIgnore(() -> keyMapper.selectOne(new LambdaQueryWrapper<AiApiKey>()
                    .eq(AiApiKey::getAppId, appId).last("LIMIT 1")));
        }
        if (entity == null || entity.getStatus() == null || entity.getStatus() != 0
                || !MessageDigest.isEqual(entity.getKeyHash().getBytes(StandardCharsets.UTF_8),
                        sha256(apiKey).getBytes(StandardCharsets.UTF_8))) {
            throw new ServiceException(401, "无效的开放 API 凭证");
        }
        return entity;
    }

    /** 每分钟限流（Redis 计数；无 Redis 时放行；纯 Redis 无 SQL，无租户上下文依赖） */
    public boolean tryAcquire(AiApiKey key) {
        if (key.getRatePerMinute() == null || key.getRatePerMinute() <= 0) {
            return true;
        }
        StringRedisTemplate redis = redisTemplate.getIfAvailable();
        if (redis == null) {
            return true;
        }
        String counterKey = "mse:ai:open:rate:" + key.getAppId() + ":" + System.currentTimeMillis() / 60_000;
        Long count = redis.opsForValue().increment(counterKey);
        if (count != null && count == 1) {
            redis.expire(counterKey, Duration.ofMinutes(2));
        }
        return count != null && count <= key.getRatePerMinute();
    }

    /** 解析 appId 世界段（ai-{tenantId}-{hex}）；旧格式（ai-{hex}，无世界段）返回 null */
    static Long parseWorld(String appId) {
        if (appId == null || !appId.startsWith("ai-")) {
            return null;
        }
        String rest = appId.substring(3);
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

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 算法", e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
