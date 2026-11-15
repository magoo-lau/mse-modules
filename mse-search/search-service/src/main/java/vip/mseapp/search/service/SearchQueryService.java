package vip.mseapp.search.service;

import cn.dev33.satoken.stp.StpLogic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.mybatis.datascope.DataScopeParam;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.engine.EsIndexManager;
import vip.mseapp.search.engine.SearchEngineClient;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.scope.SearchDataScopeResolver;
import vip.mseapp.security.stp.StpPlatformUtil;
import vip.mseapp.security.stp.StpTenantUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 搜索查询服务：三层权限过滤全部服务端强制注入，不信任任何前端入参。
 *
 * <ol>
 *   <li><b>租户隔离</b>：tenantId 取自 TenantContext（缺失快速失败），term 过滤；</li>
 *   <li><b>来源可见性</b>：权限码（permissionCode，业务契约默认 + 管理端可调）不在当前用户
 *       权限集（Sa-Token 会话，超管 * 放行）、或可见角色（visibleRoles，管理端多选配置）
 *       与当前用户角色集无交集时，整源不参与检索；两者同时生效（任一不满足即不可见）；</li>
 *   <li><b>个人数据范围</b>：SearchDataScopeResolver 解析当前用户范围（与底座 @DataPermission
 *       五档同语义），对启用过滤的源注入 bool should（公开文档 / 显式共享 / 归属人或部门范围）。</li>
 * </ol>
 */
@Service
public class SearchQueryService {

    private static final Logger log = LoggerFactory.getLogger(SearchQueryService.class);

    /** 跳转模板 {extra.字段名} 占位 */
    private static final Pattern EXTRA_TOKEN = Pattern.compile("\\{extra\\.([A-Za-z0-9_]+)\\}");

    private final EsIndexManager indexManager;
    private final SearchEngineClient engineClient;
    private final SearchSourceService sourceService;
    private final SearchDataScopeResolver dataScopeResolver;
    private final HotKeywordService hotKeywordService;
    private final SearchFieldConfigService fieldConfigService;

    public SearchQueryService(EsIndexManager indexManager, SearchEngineClient engineClient,
                              SearchSourceService sourceService, SearchDataScopeResolver dataScopeResolver,
                              HotKeywordService hotKeywordService, SearchFieldConfigService fieldConfigService) {
        this.indexManager = indexManager;
        this.engineClient = engineClient;
        this.sourceService = sourceService;
        this.dataScopeResolver = dataScopeResolver;
        this.hotKeywordService = hotKeywordService;
        this.fieldConfigService = fieldConfigService;
    }

    /**
     * 全局搜索。
     *
     * @param sourceCodes 指定参与的源（空 = 全部可见源）
     * @param domain 业务域过滤（空 = 全部域；仅 domain 等值匹配的源参与检索）
     * @param ignoreDataScope 平台管理端调试用（SearchPlayground）；调用方自行把关权限
     */
    public SearchQueryResult query(String keyword, List<String> sourceCodes, String domain,
                                   long pageNum, long pageSize, boolean ignoreDataScope) {
        String kw = keyword == null ? "" : keyword.trim();
        Long tenantId = TenantContext.getRequiredTenantId();
        if (kw.isEmpty()) {
            return new SearchQueryResult(0, List.of(), List.of());
        }
        // 第 2 层：来源可见性过滤（权限码 + 可见角色；无可见源的空结果）；域过滤在源目录层展开为源子集，不动 ES 索引
        List<SearchSource> visibleSources = userVisibleSources().stream()
                .filter(s -> sourceCodes == null || sourceCodes.isEmpty() || sourceCodes.contains(s.getSourceCode()))
                .filter(s -> domain == null || domain.isBlank() || domain.equals(s.getDomain()))
                .toList();
        if (visibleSources.isEmpty()) {
            return new SearchQueryResult(0, List.of(), List.of());
        }
        List<String> indices = visibleSources.stream()
                .map(s -> indexManager.indexName(s.getSourceCode(), tenantId))
                .toList();

        // 过滤条件：第 1 层租户 + 第 3 层个人数据范围
        List<Object> filter = new ArrayList<>();
        filter.add(term("tenantId", String.valueOf(tenantId)));
        DataScopeParam scope = ignoreDataScope ? null : dataScopeResolver.resolve();
        Long userId = UserContext.getUserId();
        if (scope != null && userId != null) {
            filter.add(personalFilter(scope, userId));
        }

        int size = (int) Math.min(Math.max(pageSize, 1), 50);
        int from = (int) Math.max(pageNum - 1, 0) * size;
        // 源字段配置：平台配置 + 当前租户覆盖 → 检索/高亮字段（跨源同名取最大权重；无配置回退内置字段）
        Map<String, List<SearchFieldDescriptor>> overrides = fieldConfigService.loadTenantOverrides(
                visibleSources.stream().map(SearchSource::getSourceCode).toList());
        Map<String, Double> searchWeights = new LinkedHashMap<>();
        List<String> highlightFields = new ArrayList<>();
        for (SearchSource source : visibleSources) {
            List<SearchFieldDescriptor> effective = fieldConfigService.mergeTenant(
                    fieldConfigService.parsePlatform(source.getFieldsJson()),
                    overrides.get(source.getSourceCode()));
            fieldConfigService.searchableWeights(effective)
                    .forEach((field, weight) -> searchWeights.merge(field, weight, Math::max));
            for (String field : fieldConfigService.highlightFields(effective)) {
                if (!highlightFields.contains(field)) {
                    highlightFields.add(field);
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        List<String> matchFields = new ArrayList<>();
        // 内置检索字段恒在（title^3 + content），配置字段为追加；无配置时请求体与历史行为完全一致
        matchFields.add("title^3");
        matchFields.add("content");
        searchWeights.forEach((field, weight) -> matchFields.add(weight == 1.0 ? field : field + "^" + trimWeight(weight)));
        body.put("query", Map.of("bool", Map.of(
                "must", List.of(Map.of("multi_match", Map.of(
                        "query", kw,
                        "fields", matchFields))),
                "filter", filter)));
        body.put("from", from);
        body.put("size", size);
        body.put("track_total_hits", true);
        body.put("sort", List.of("_score", Map.of("updateTime", Map.of("order", "desc", "unmapped_type", "date"))));
        // fields 必须是「单键对象数组」而非 map：ES 9.2 起该字段 wire 格式改为数组，
        // elasticsearch-java 9.x 类型化客户端 withJson 解析 map 形态会抛 UnexpectedJsonEventException
        List<Object> highlightFieldSpecs = new ArrayList<>();
        highlightFieldSpecs.add(Map.of("title", Map.of("number_of_fragments", 0)));
        highlightFieldSpecs.add(Map.of("content", Map.of("fragment_size", 120, "number_of_fragments", 2)));
        for (String field : highlightFields) {
            highlightFieldSpecs.add(Map.of(field, Map.of("fragment_size", 120, "number_of_fragments", 2)));
        }
        // encoder=html：字段原文先行 HTML 转义（防检索内容毒化标签 XSS，审计 SEC-01/F-01），
        // 高亮标签 <em> 为转义后注入、保持字面输出；前端渲染侧配套白名单消毒（mse-ui-shared sanitizeSearchHighlight）
        body.put("highlight", Map.of("pre_tags", List.of("<em>"), "post_tags", List.of("</em>"),
                "encoder", "html", "fields", highlightFieldSpecs));
        body.put("aggs", Map.of("by_source", Map.of("terms", Map.of("field", "sourceCode", "size", 30))));

        Map<String, Object> response = engineClient.search(indices, body);
        long total = ((Number) response.getOrDefault("total", 0L)).longValue();

        Map<String, SearchSource> sourceByCode = new HashMap<>();
        visibleSources.forEach(s -> sourceByCode.put(s.getSourceCode(), s));

        List<SearchResultItem> items = new ArrayList<>();
        for (Object raw : (List<?>) response.getOrDefault("hits", List.of())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = (Map<String, Object>) raw;
            @SuppressWarnings("unchecked")
            Map<String, Object> docSource = (Map<String, Object>) hit.get("_source");
            if (docSource == null) {
                continue;
            }
            items.add(toItem(hit, docSource, sourceByCode));
        }

        List<SearchGroupCount> groups = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> aggregations = (Map<String, Object>) response.getOrDefault("aggregations", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> bySource = (Map<String, Object>) aggregations.get("by_source");
        if (bySource != null) {
            for (Object rawBucket : (List<?>) bySource.getOrDefault("buckets", List.of())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> bucket = (Map<String, Object>) rawBucket;
                String code = String.valueOf(bucket.get("key"));
                groups.add(new SearchGroupCount(code,
                        sourceByCode.containsKey(code) ? sourceByCode.get(code).getSourceName() : code,
                        ((Number) bucket.getOrDefault("count", 0)).longValue()));
            }
        }

        hotKeywordService.record(kw);
        return new SearchQueryResult(total, items, groups);
    }

    /** 搜索框联想：本租户热词（前缀匹配） */
    public List<String> suggest(String keyword, int limit) {
        return hotKeywordService.topKeywords(keyword == null ? "" : keyword.trim(), limit);
    }

    /**
     * 域选项：当前用户权限可见源的业务域去重清单（与 query 第 2 层同语义；未分域的源不出现在选项，
     * 仅参与「全部域」）。前端据此渲染「全部域 + 各域」chips。
     */
    public List<String> visibleDomains() {
        return userVisibleSources().stream()
                .map(SearchSource::getDomain)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 展示字段元数据：当前用户可见源 ×（平台配置 + 当前租户覆盖）的 returned 字段（key+label）。
     * 搜索端据此把结果 extra 中的值渲染为带中文标签的字段项。
     */
    public List<SourceDisplayFields> displayFields() {
        List<SearchSource> visibleSources = userVisibleSources();
        Map<String, List<SearchFieldDescriptor>> overrides = fieldConfigService.loadTenantOverrides(
                visibleSources.stream().map(SearchSource::getSourceCode).toList());
        List<SourceDisplayFields> result = new ArrayList<>();
        for (SearchSource source : visibleSources) {
            List<SearchFieldDescriptor> effective = fieldConfigService.mergeTenant(
                    fieldConfigService.parsePlatform(source.getFieldsJson()),
                    overrides.get(source.getSourceCode()));
            List<FieldLabel> fields = fieldConfigService.displayFields(effective).stream()
                    .map(f -> new FieldLabel(f.getField(), f.getLabel()))
                    .toList();
            if (!fields.isEmpty()) {
                result.add(new SourceDisplayFields(source.getSourceCode(), source.getSourceName(), fields));
            }
        }
        return result;
    }

    /** 启用源 + 来源可见性过滤（权限码 + 可见角色；查询与域选项共用的可见性口径） */
    private List<SearchSource> userVisibleSources() {
        List<String> perms = currentPermissions();
        List<String> roles = currentRoles();
        boolean wildcard = perms.contains("*");
        return sourceService.listEnabled().stream()
                .filter(s -> wildcard || s.getPermissionCode() == null || perms.contains(s.getPermissionCode()))
                .filter(s -> {
                    List<String> requiredRoles = SearchSourceService.parseRoles(s.getVisibleRoles());
                    return requiredRoles.isEmpty() || requiredRoles.stream().anyMatch(roles::contains);
                })
                .toList();
    }

    // ==================== 三层过滤的组装 ====================

    /**
     * 个人数据范围过滤：公开文档 OR 显式共享 OR 范围内归属（SELF=归属人；CUSTOM/DEPT=部门并集）。
     */
    private Map<String, Object> personalFilter(DataScopeParam scope, Long userId) {
        List<Object> should = new ArrayList<>();
        should.add(term("visibleType", SearchIndexService.VISIBLE_TYPE_ALL));
        should.add(term("visibleUserIds", String.valueOf(userId)));
        if (scope.type() == DataScopeParam.Type.SELF) {
            should.add(term("ownerUserId", String.valueOf(scope.userId())));
        } else if (scope.deptIds() != null && !scope.deptIds().isEmpty()) {
            should.add(terms("deptId", scope.deptIds().stream().map(String::valueOf).toList()));
        }
        return Map.of("bool", Map.of("should", should, "minimum_should_match", 1));
    }

    /** 当前用户权限码（双端登录各自取；超管为 *） */
    private static List<String> currentPermissions() {
        try {
            if (StpTenantUtil.isLogin()) {
                return StpTenantUtil.stpLogic.getPermissionList();
            }
            if (StpPlatformUtil.isLogin()) {
                return StpPlatformUtil.stpLogic.getPermissionList();
            }
        } catch (Exception e) {
            log.warn("[mse-search] 权限集获取失败，按无权限处理: {}", e.getMessage());
        }
        return List.of();
    }

    /** 当前用户角色编码（双端登录各自取，经基座/RPC 鉴权数据源缓存；获取失败按无角色处理） */
    private static List<String> currentRoles() {
        try {
            if (StpTenantUtil.isLogin()) {
                return StpTenantUtil.stpLogic.getRoleList();
            }
            if (StpPlatformUtil.isLogin()) {
                return StpPlatformUtil.stpLogic.getRoleList();
            }
        } catch (Exception e) {
            log.warn("[mse-search] 角色集获取失败，按无角色处理: {}", e.getMessage());
        }
        return List.of();
    }

    private static Map<String, Object> term(String field, String value) {
        return Map.of("term", Map.of(field, value));
    }

    /** 权重格式化：2.0 → "2"、2.5 → "2.5"（multi_match 的 ^boost 语法） */
    private static String trimWeight(double weight) {
        return weight == Math.floor(weight) ? String.valueOf((long) weight) : String.valueOf(weight);
    }

    private static Map<String, Object> terms(String field, List<String> values) {
        return Map.of("terms", Map.of(field, values));
    }

    // ==================== 结果组装 ====================

    @SuppressWarnings("unchecked")
    private SearchResultItem toItem(Map<String, Object> hit, Map<String, Object> docSource,
                                    Map<String, SearchSource> sourceByCode) {
        String sourceCode = String.valueOf(docSource.getOrDefault("sourceCode", ""));
        String bizId = String.valueOf(docSource.getOrDefault("bizId", ""));
        Map<String, Object> highlight = (Map<String, Object>) hit.get("highlight");
        String title = highlighted(highlight, "title", docSource.get("title"));
        String content = highlighted(highlight, "content", docSource.get("content"));
        SearchSource source = sourceByCode.get(sourceCode);
        String jumpUrl = renderJumpUrl(source, bizId,
                (Map<String, Object>) docSource.getOrDefault("extra", Map.of()));
        Object score = hit.get("_score");
        return new SearchResultItem(
                String.valueOf(hit.get("_id")),
                sourceCode,
                source == null ? sourceCode : source.getSourceName(),
                bizId,
                title,
                content,
                jumpUrl,
                String.valueOf(docSource.getOrDefault("updateTime", "")),
                score instanceof Number number ? number.doubleValue() : null,
                (Map<String, Object>) docSource.get("extra"));
    }

    @SuppressWarnings("unchecked")
    /**
     * 高亮片段取值：命中字段取引擎片段（encoder=html 已转义、仅 <em> 为真实标签）；
     * 未命中字段回退 _source 原文——必须同口径 HTML 转义（防检索内容毒化标签经无高亮回退路径直出）。
     */
    private static String highlighted(Map<String, Object> highlight, String field, Object fallback) {
        if (highlight != null && highlight.containsKey(field)) {
            List<String> fragments = (List<String>) highlight.get(field);
            if (fragments != null && !fragments.isEmpty()) {
                return String.join("…", fragments);
            }
        }
        String value = fallback == null ? "" : String.valueOf(fallback)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return value.length() > 124 ? value.substring(0, 124) + "…" : value;
    }

    /** 跳转模板渲染：{bizId} 与 {extra.字段} 占位（未匹配的 extra 占位替换为空串） */
    private static String renderJumpUrl(SearchSource source, String bizId, Map<String, Object> extra) {
        if (source == null || source.getJumpUrlTemplate() == null) {
            return null;
        }
        String url = source.getJumpUrlTemplate().replace("{bizId}", bizId == null ? "" : bizId);
        Matcher matcher = EXTRA_TOKEN.matcher(url);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            Object value = extra == null ? null : extra.get(matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** 搜索结果条目 */
    public record SearchResultItem(String id, String sourceCode, String sourceName, String bizId,
                                   String title, String content, String jumpUrl, String updateTime,
                                   Double score, Map<String, Object> extra) {
    }

    /** 按源分组计数（结果页签展示） */
    public record SearchGroupCount(String sourceCode, String sourceName, long count) {
    }

    /** 查询结果 */
    public record SearchQueryResult(long total, List<SearchResultItem> records, List<SearchGroupCount> groups) {
    }

    /** 源的展示字段元数据（搜索端渲染 extra 值的依据） */
    public record SourceDisplayFields(String sourceCode, String sourceName, List<FieldLabel> fields) {
    }

    /** 展示字段：extra 键 + 显示名 */
    public record FieldLabel(String field, String label) {
    }
}
