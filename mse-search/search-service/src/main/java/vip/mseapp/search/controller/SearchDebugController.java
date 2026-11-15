package vip.mseapp.search.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.Result;
import vip.mseapp.search.api.SearchIndexPayload;
import vip.mseapp.search.engine.EsIndexManager;
import vip.mseapp.search.engine.SearchEngineClient;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.service.SearchDebugOptionService;
import vip.mseapp.search.service.SearchIndexService;
import vip.mseapp.search.service.SearchQueryService;
import vip.mseapp.search.service.SearchSourceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索调试（租户端，验收辅助）：手工推送测试文档直通索引（无业务接入时验证引擎链路），
 * 查询支持"忽略个人数据过滤"对比——用于验证三层过滤生效。
 *
 * <p>租户会话的调试域锁定为会话租户（请求携带的 tenantId 被忽略，防越权指定）；
 * 平台会话（tenant 0）保持平台域调试语义。
 *
 * <p>选项接口为推送/查询表单提供下拉数据（源/租户/用户/部门），避免手输编号。
 */
@Validated
@RestController
@RequestMapping("/search/debug")
public class SearchDebugController {

    private final SearchIndexService indexService;
    private final SearchQueryService queryService;
    private final SearchDebugOptionService optionService;
    private final SearchEngineClient engineClient;
    private final EsIndexManager indexManager;
    private final SearchSourceService sourceService;

    public SearchDebugController(SearchIndexService indexService, SearchQueryService queryService,
                                 SearchDebugOptionService optionService, SearchEngineClient engineClient,
                                 EsIndexManager indexManager, SearchSourceService sourceService) {
        this.indexService = indexService;
        this.queryService = queryService;
        this.optionService = optionService;
        this.engineClient = engineClient;
        this.indexManager = indexManager;
        this.sourceService = sourceService;
    }

    /** 推送/查询表单的下拉选项；归属域租户会话锁定为本租户，平台会话默认平台域 0 */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/options")
    public Result<SearchDebugOptionService.DebugOptions> options(
            @RequestParam(name = "tenantId", required = false) Long tenantId) {
        return Result.ok(optionService.load(resolveTenantId(tenantId)));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/push-doc")
    public Result<Void> pushDoc(@RequestBody @Validated DebugDocRequest request) {
        long tenantId = resolveTenantId(request.tenantId());
        SearchIndexPayload payload = new SearchIndexPayload();
        payload.setSourceCode(request.sourceCode());
        payload.setBizId(request.bizId());
        payload.setTitle(request.title());
        payload.setContent(request.content());
        payload.setExtra(request.extra());
        payload.setUpdateTime(request.updateTime());
        payload.setDeleted(Boolean.TRUE.equals(request.deleted()));
        payload.setOwnerUserId(request.ownerUserId());
        payload.setDeptId(request.deptId());
        payload.setVisibleType(request.visibleType());
        payload.setVisibleUserIds(request.visibleUserIds());
        TenantContext.runAs(tenantId, () -> {
            if (payload.isDeleted()) {
                indexService.applyDelete(tenantId, payload);
            } else {
                indexService.applyUpsert(tenantId, payload);
            }
            return null;
        });
        return Result.ok();
    }

    /** 可选忽略个人数据范围的调试查询（验收三层过滤；租户会话域锁定本租户） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/query")
    public Result<SearchQueryService.SearchQueryResult> query(@RequestBody @Validated DebugQueryRequest request) {
        long tenantId = resolveTenantId(request.tenantId());
        SearchQueryService.SearchQueryResult result = TenantContext.runAs(tenantId,
                () -> queryService.query(request.keyword(), request.sourceCodes(), request.domain(),
                        request.pageNum() == null ? 1 : request.pageNum(),
                        request.pageSize() == null ? 10 : request.pageSize(),
                        Boolean.TRUE.equals(request.ignoreDataScope())));
        return Result.ok(result);
    }

    /**
     * 从归属租户的源索引采样文档聚合字段键（配置界面的「从数据采样」）：union(extra 键, 顶层提升键)，
     * 供表单数据这类动态 schema 的字段发现；采样索引 = 会话租户 × 源。
     */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:debug:use", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/sample-fields")
    public Result<List<String>> sampleFields(@RequestParam String sourceCode,
                                             @RequestParam(defaultValue = "20") int size) {
        SearchSource source = sourceService.selectByCode(sourceCode);
        if (source == null) {
            throw new ServiceException(404, "可搜索源未登记: " + sourceCode);
        }
        Map<String, Object> body = Map.of(
                "query", Map.of("match_all", Map.of()),
                "size", Math.min(Math.max(size, 1), 100));
        Map<String, Object> response = engineClient.search(
                List.of(indexManager.indexName(source.getSourceCode(), TenantContext.getRequiredTenantId())), body);
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (Object raw : (List<?>) response.getOrDefault("hits", List.of())) {
            @SuppressWarnings("unchecked")
            Map<String, Object> hit = (Map<String, Object>) raw;
            @SuppressWarnings("unchecked")
            Map<String, Object> docSource = (Map<String, Object>) hit.get("_source");
            if (docSource == null) {
                continue;
            }
            docSource.forEach((key, value) -> {
                if (value != null && !BASE_FIELDS.contains(key)) {
                    keys.add(key);
                }
            });
            Object extra = docSource.get("extra");
            if (extra instanceof Map<?, ?> extraMap) {
                extraMap.forEach((key, value) -> {
                    if (value != null) {
                        keys.add(String.valueOf(key));
                    }
                });
            }
        }
        return Result.ok(new ArrayList<>(keys));
    }

    /**
     * 调试归属域解析：租户会话一律锁定会话租户（请求传入的 tenantId 仅平台会话可指定，防伪造越权）；
     * 平台会话取请求值（缺省平台域 0）。
     */
    private static long resolveTenantId(Long requested) {
        Long ctx = TenantContext.getTenantId();
        if (ctx != null && ctx != 0L) {
            return ctx;
        }
        return requested == null ? 0L : requested;
    }

    /** 采样时排除的系统字段（extra 之外的基座/权限字段） */
    private static final java.util.Set<String> BASE_FIELDS = java.util.Set.of(
            "title", "content", "extra", "tenantId", "sourceCode", "bizId", "permissionCode",
            "ownerUserId", "deptId", "visibleType", "visibleUserIds", "updateTime");

    public record DebugDocRequest(@NotBlank(message = "sourceCode 不能为空") String sourceCode,
                                  @NotBlank(message = "bizId 不能为空") String bizId,
                                  Long tenantId, String title, String content, Map<String, Object> extra,
                                  String updateTime, Boolean deleted, Long ownerUserId, Long deptId,
                                  String visibleType, List<Long> visibleUserIds) {
    }

    public record DebugQueryRequest(@NotBlank(message = "关键词不能为空") String keyword,
                                    List<String> sourceCodes, String domain, Long tenantId, Long pageNum,
                                    Long pageSize, Boolean ignoreDataScope) {
    }
}
