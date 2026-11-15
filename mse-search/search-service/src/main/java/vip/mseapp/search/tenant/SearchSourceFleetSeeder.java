package vip.mseapp.search.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.search.api.SearchSourceDescriptor;
import vip.mseapp.search.service.SearchSourceService;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 可搜索源目录扇出播种器：把业务自注册的源描述写入「平台 + 全部租户」各自目录。
 *
 * <p>目录真租户化后每租户一份 {@code search_source}：逐归属上下文 {@code runAs} upsert——
 * 单体双库形态平台落平台库、业务服务未注册 platform 数据源时回落主库（tenant_id=0 行），
 * 租户由路由拦截器切归属库（独立库 = 租户库，共享池 = 主库 tenant_id 行）。单上下文失败
 * 只记日志不阻断其余，等下轮业务推送（应用就绪 + 周期重推自愈）覆盖补齐——新租户建库后
 * 最迟一个推送周期内目录自动就绪。
 *
 * <p>upsert 语义复用 {@link SearchSourceService#registerDescriptors}：描述字段随推送刷新，
 * 各租户管理端调过的扫描参数/启停/字段定制不被覆盖。
 */
@Component
public class SearchSourceFleetSeeder {

    private static final Logger log = LoggerFactory.getLogger(SearchSourceFleetSeeder.class);

    private final SearchSourceService sourceService;
    private final SearchTenantRegistry tenantRegistry;
    private final ExecutorService searchExecutor;

    public SearchSourceFleetSeeder(SearchSourceService sourceService,
                                   SearchTenantRegistry tenantRegistry,
                                   ExecutorService searchExecutor) {
        this.sourceService = sourceService;
        this.tenantRegistry = tenantRegistry;
        this.searchExecutor = searchExecutor;
    }

    /** 异步扇出（不阻塞注册 RPC）；descriptors 为空直接跳过 */
    public void seedAsync(List<SearchSourceDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return;
        }
        searchExecutor.submit(() -> {
            try {
                seedAll(descriptors);
            } catch (Throwable e) {
                log.warn("[mse-search] 源目录扇出播种异常（等待下轮推送重试）: {}", e.getMessage());
            }
        });
    }

    /** 同步扇出：平台 + 逐租户 upsert；单上下文失败不阻断其余 */
    public void seedAll(List<SearchSourceDescriptor> descriptors) {
        for (Long tenantId : tenantRegistry.contextTenantIds()) {
            try {
                TenantContext.runAs(tenantId, () -> sourceService.registerDescriptors(descriptors));
                log.info("[mse-search] 源目录播种完成: tenant={} sources={}", tenantId, descriptors.size());
            } catch (Exception e) {
                log.warn("[mse-search] 源目录播种失败（等下轮推送自愈）: tenant={} err={}", tenantId, e.getMessage());
            }
        }
    }
}
