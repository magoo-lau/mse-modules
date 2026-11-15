package vip.mseapp.open.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.open.entity.OpenApiDef;
import vip.mseapp.open.mapper.OpenApiMapper;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开放 API 目录：启用中的 API 清单缓存 + 请求匹配（目录真租户化——每会话世界一份目录：
 * 租户会话=本租户目录行，平台会话/平台域凭证=平台世界行 tenant_id=0）。
 *
 * <p>清单内存缓存按世界分桶、30s 惰性刷新（管理端变更最迟 30s 生效，开放调用高频路径零 DB 开销）；
 * 匹配规则：HTTP 方法相等或清单为 ALL，且路径命中 Ant 模式；同路径既可精确命中又有
 * 模板命中时取最长模式（如 /open/api/user/by-username 优先于 /open/api/user/{id}）。
 */
@Service
public class OpenApiCatalogService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final OpenApiMapper apiMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** 各世界的启用清单缓存（key=归属租户编号；开放调用按 key 归属世界匹配） */
    private final ConcurrentHashMap<Long, WorldCache> caches = new ConcurrentHashMap<>();

    /** 单世界缓存条目（惰性刷新 + 条目级锁，互不影响） */
    private static final class WorldCache {
        private volatile List<OpenApiDef> apis;
        private volatile long loadedAt;

        private List<OpenApiDef> get(long world, OpenApiMapper apiMapper) {
            List<OpenApiDef> snapshot = apis;
            long loaded = loadedAt;
            long now = System.nanoTime();
            if (snapshot != null && now - loaded < CACHE_TTL.toNanos()) {
                return snapshot;
            }
            synchronized (this) {
                if (apis != null && System.nanoTime() - loadedAt < CACHE_TTL.toNanos()) {
                    return apis;
                }
                // 目录行在业务库：runIgnore + 显式 tenant 条件，不随平台上下文路由平台库（平台库无此表）
                List<OpenApiDef> fresh = TenantContext.runIgnore(() ->
                        apiMapper.selectList(new LambdaQueryWrapper<OpenApiDef>()
                                .eq(OpenApiDef::getTenantId, world)
                                .eq(OpenApiDef::getStatus, OpenApiDef.STATUS_ENABLED)));
                apis = fresh;
                loadedAt = System.nanoTime();
                return fresh;
            }
        }
    }

    public OpenApiCatalogService(OpenApiMapper apiMapper) {
        this.apiMapper = apiMapper;
    }

    /** 当前会话世界编号（无上下文按平台世界 0 处理，供开放调用鉴权前的兜底） */
    private static long worldId() {
        Long tenantId = TenantContext.getTenantId();
        return tenantId == null ? MseConstants.PLATFORM_TENANT_ID : tenantId;
    }

    /** 匹配请求命中的 API（本世界目录）；未开放返回 null */
    public OpenApiDef match(String method, String path) {
        String normalized = method == null ? "" : method.toUpperCase(Locale.ROOT);
        // 特异性排序：精确字面路径优先于模板（/open/api/user/page 必须压过 /open/api/user/{id}），
        // 同类中取更长模式（静态前缀更长），末位按 id 升序保证确定性
        Comparator<OpenApiDef> specificity = Comparator
                .comparing((OpenApiDef api) -> isTemplate(api.getPath()))
                .thenComparing(api -> api.getPath().length(), Comparator.reverseOrder())
                .thenComparing(OpenApiDef::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        return enabledApis().stream()
                .filter(api -> methodMatches(api.getHttpMethod(), normalized))
                .filter(api -> pathMatcher.match(api.getPath(), path))
                .min(specificity)
                .orElse(null);
    }

    /** 本世界全量启用清单（管理端展示/授权弹窗用） */
    public List<OpenApiDef> listEnabled() {
        return enabledApis();
    }

    /**
     * 本世界全量目录（含停用；目录治理页用，按状态过滤走 DB 直查不走缓存）。
     * runIgnore + 显式 tenant 条件：租户会话读本租户目录行、平台会话读平台世界行
     * （分库部署下目录表只在业务库，不随平台会话路由平台库）。
     */
    public List<OpenApiDef> listAll(Integer status) {
        long world = worldId();
        return TenantContext.runIgnore(() -> apiMapper.selectList(new LambdaQueryWrapper<OpenApiDef>()
                .eq(OpenApiDef::getTenantId, world)
                .eq(status != null, OpenApiDef::getStatus, status)
                .orderByAsc(OpenApiDef::getCode)));
    }

    /** 立即失效缓存（管理端 API 变更/扇出播种时调用，全部世界一并失效） */
    public void evict() {
        caches.clear();
    }

    private List<OpenApiDef> enabledApis() {
        return caches.computeIfAbsent(worldId(), w -> new WorldCache()).get(worldId(), apiMapper);
    }

    private boolean methodMatches(String apiMethod, String requestMethod) {
        if (apiMethod == null || apiMethod.isBlank()) {
            return true;
        }
        String upper = apiMethod.trim().toUpperCase(Locale.ROOT);
        return "ALL".equals(upper) || upper.equals(requestMethod);
    }

    /** 路径模板判定：含 Ant 变量段或通配符即为模板（特异性低于字面路径） */
    private boolean isTemplate(String path) {
        return path.contains("{") || path.contains("?") || path.contains("*");
    }
}
