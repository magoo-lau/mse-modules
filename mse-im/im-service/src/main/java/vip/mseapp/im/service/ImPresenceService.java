package vip.mseapp.im.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 在线状态（Redis presence）：afterUserOnline/Offline/KickOff 回调维护
 * {@code im:online:{tenantId}:{platformId}} 集合（7 天 TTL 自愈），供工作台/机器人侧查询。
 */
@Service
public class ImPresenceService {

    private static final String KEY_PREFIX = "im:online:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public ImPresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void online(Long tenantId, String userID, int platformId) {
        redis.opsForSet().add(key(tenantId, platformId), userID);
        redis.expire(key(tenantId, platformId), TTL);
    }

    public void offline(Long tenantId, String userID, int platformId) {
        redis.opsForSet().remove(key(tenantId, platformId), userID);
    }

    /** 批量查询：userID → 是否在线（platformId 缺省 5=Web） */
    public Map<String, Boolean> filterOnline(Long tenantId, int platformId, Iterable<String> userIDs) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        String key = key(tenantId, platformId);
        for (String userID : userIDs) {
            Boolean member = redis.opsForSet().isMember(key, userID);
            out.put(userID, Boolean.TRUE.equals(member));
        }
        return out;
    }

    /** 当前租户某端型的在线 userID 集合（管理/联调用） */
    public Map<String, Object> listOnline(Long tenantId, int platformId) {
        var members = redis.opsForSet().members(key(tenantId, platformId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platformId", platformId);
        out.put("userIds", members == null ? java.util.Set.of() : members);
        return out;
    }

    private static String key(Long tenantId, int platformId) {
        // 回调链路无租户上下文时由调用方 runAs 恢复；此处兜底防御性取值
        Long tid = TenantContext.getTenantId() == null ? tenantId : TenantContext.getTenantId();
        return KEY_PREFIX + tid + ":" + platformId;
    }
}
