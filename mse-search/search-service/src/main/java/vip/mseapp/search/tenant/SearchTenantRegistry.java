package vip.mseapp.search.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.api.tenant.RemoteTenantService;
import vip.mseapp.api.tenant.dto.TenantDTO;
import vip.mseapp.core.annotation.RpcReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 搜索引擎租户清单注册器：枚举「平台 + 全部租户」归属上下文，供目录扇出播种与扫描/任务
 * 调度按归属遍历（调用方对每个编号 {@code TenantContext.runAs} 进入）。
 *
 * <p>租户清单经 api-tenant 契约拉取（单体 = 本地实现，微服务 = dubbo），60s 缓存 +
 * last-good 兜底（控制面暂不可用时沿用上一次清单，绝不因此中断调度）。平台域（tenant 0）
 * 与租户统一走 runAs：单体双库形态路由平台库、业务服务未注册 platform 数据源时回落主库
 * （tenant_id=0 行 + 行级过滤），与 mse-monitor 双端语义一致。
 */
@Component
public class SearchTenantRegistry {

    /** 平台域租户编号 */
    public static final long PLATFORM_TENANT_ID = 0L;

    private static final Logger log = LoggerFactory.getLogger(SearchTenantRegistry.class);

    private static final long CACHE_TTL_MS = 60_000L;

    @RpcReference
    private RemoteTenantService remoteTenantService;

    private volatile List<Long> cached = List.of();
    private volatile long cachedAt = 0;

    /**
     * 归属上下文编号清单：平台 0（首位）+ 全部租户（含停用——目录/扫描与租户启停状态解耦，
     * 数据生命周期独立）。
     */
    public List<Long> contextTenantIds() {
        List<Long> ids = new ArrayList<>();
        ids.add(PLATFORM_TENANT_ID);
        ids.addAll(tenantIds());
        return ids;
    }

    /**
     * 全部租户编号（含停用；不含平台 0）。
     */
    public List<Long> tenantIds() {
        List<Long> snapshot = cached;
        if (!snapshot.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return snapshot;
        }
        synchronized (this) {
            if (!cached.isEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return cached;
            }
            try {
                List<Long> ids = remoteTenantService.listTenants().stream()
                        .map(TenantDTO::getId)
                        .filter(Objects::nonNull)
                        .toList();
                if (!ids.isEmpty()) {
                    cached = ids;
                    cachedAt = System.currentTimeMillis();
                }
                return ids;
            } catch (Exception e) {
                log.warn("[mse-search] 租户清单拉取失败，沿用上次缓存({} 个): {}", cached.size(), e.getMessage());
                return cached;
            }
        }
    }
}
