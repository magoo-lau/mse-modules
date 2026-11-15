package vip.mseapp.search.service;

import org.springframework.stereotype.Service;
import vip.mseapp.search.api.dto.SearchFieldCatalogConflict;
import vip.mseapp.search.api.dto.SearchFieldCatalogEntry;
import vip.mseapp.search.api.dto.SearchFieldCatalogResult;
import vip.mseapp.search.dispatch.SearchProviderDispatcher;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.support.SearchJson;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务字段目录拉取：按源经 {@link SearchProviderDispatcher} 向业务 Provider 取
 * {@code fieldCatalog}（业务侧实时生成，不落库），并对目录条目标注收录状态。
 *
 * <p>目录不可用（业务未提供/服务不可达/超时）一律降级为 available=false + 原因，不阻断
 * 字段配置页与检索链路。条目状态：platform=已在平台配置、tenant=本租户新增、available=可收录。
 */
@Service
public class SearchFieldCatalogService {

    /** 条目状态：已在平台配置 */
    public static final String STATUS_PLATFORM = "platform";
    /** 条目状态：本租户新增（仅租户视图出现） */
    public static final String STATUS_TENANT = "tenant";
    /** 条目状态：可收录（平台未配置 / 本租户未新增） */
    public static final String STATUS_AVAILABLE = "available";

    private final SearchProviderDispatcher dispatcher;

    public SearchFieldCatalogService(SearchProviderDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 拉取指定源的业务字段目录（失败降级为 available=false + 原因，不抛异常）。
     * tenantId 显式随请求下传（供数契约不依赖上下文透传，与扫描拉取同范式）——
     * 业务侧按其过滤目录世界（租户端=本租户表单，平台端=平台世界）。
     */
    public CatalogFetch fetch(SearchSource source, long tenantId) {
        try {
            String json = dispatcher.catalog(source.getProviderGroup(),
                    SearchJson.toJson(Map.of("sourceCode", String.valueOf(source.getSourceCode()),
                            "tenantId", tenantId)));
            if (json == null || json.isBlank()) {
                return new CatalogFetch(false, "业务侧未提供字段目录（需业务积木实现 fieldCatalog 契约）",
                        List.of(), List.of(), false);
            }
            SearchFieldCatalogResult result = SearchJson.parse(json, SearchFieldCatalogResult.class);
            return new CatalogFetch(true, result.isTruncated() ? "目录条目过多，业务侧已截断" : null,
                    result.getEntries() == null ? List.of() : result.getEntries(),
                    result.getConflicts() == null ? List.of() : result.getConflicts(),
                    result.isTruncated());
        } catch (Exception e) {
            return new CatalogFetch(false, "字段目录拉取失败：" + e.getMessage(), List.of(), List.of(), false);
        }
    }

    /** 平台目录视图：条目标注是否已被平台配置收录（冲突条目单独返回） */
    public CatalogView platformView(SearchSource source, Set<String> platformFields) {
        CatalogFetch fetch = fetch(source, requestWorldId());
        return new CatalogView(fetch.available(), fetch.message(), fetch.truncated(),
                annotate(fetch, platformFields, Set.of()), fetch.conflicts());
    }

    /** 租户目录视图：条目标注平台收录 / 本租户新增 / 可收录（冲突条目单独返回） */
    public CatalogView tenantView(SearchSource source, Set<String> platformFields, Set<String> tenantAddedFields) {
        CatalogFetch fetch = fetch(source, requestWorldId());
        return new CatalogView(fetch.available(), fetch.message(), fetch.truncated(),
                annotate(fetch, platformFields, tenantAddedFields), fetch.conflicts());
    }

    /** 当前会话世界编号（无上下文按平台 0 兜底） */
    public static long requestWorldId() {
        Long tenantId = vip.mseapp.core.context.TenantContext.getTenantId();
        return tenantId == null ? 0L : tenantId;
    }

    /** 收录状态标注：platformNames 命中 → platform；tenantNames 命中 → tenant；否则 available */
    public List<CatalogEntryView> annotate(CatalogFetch fetch, Set<String> platformNames, Set<String> tenantNames) {
        return fetch.entries().stream()
                .map(entry -> new CatalogEntryView(entry.getField(), entry.getLabel(), entry.getType(),
                        entry.getGroup(), entry.getFormCount(),
                        platformNames.contains(entry.getField()) ? STATUS_PLATFORM
                                : tenantNames.contains(entry.getField()) ? STATUS_TENANT : STATUS_AVAILABLE))
                .toList();
    }

    /** 目录拉取原始结果 */
    public record CatalogFetch(boolean available, String message,
                               List<SearchFieldCatalogEntry> entries,
                               List<SearchFieldCatalogConflict> conflicts,
                               boolean truncated) {
    }

    /** 标注收录状态后的目录条目 */
    public record CatalogEntryView(String field, String label, String type, String group,
                                   Integer formCount, String status) {
    }

    /** 目录视图：条目 + 冲突 + 可用性 */
    public record CatalogView(boolean available, String message, boolean truncated,
                              List<CatalogEntryView> entries,
                              List<SearchFieldCatalogConflict> conflicts) {
    }
}
