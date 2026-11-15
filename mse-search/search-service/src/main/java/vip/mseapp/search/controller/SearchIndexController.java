package vip.mseapp.search.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.Result;
import vip.mseapp.search.config.SearchProperties;
import vip.mseapp.search.engine.SearchEngineClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引管理（租户端）：列出归属租户的引擎索引清单（含源已删除后遗留的索引）、浏览索引文档
 * （关键词过滤）、清空索引数据（保留结构）、删除索引。
 *
 * <p>所有按索引名的操作均校验本引擎前缀（index-prefix-*）+ 会话租户后缀（-tenantId），
 * 禁止越权操作其他引擎/其他租户的索引。
 */
@RestController
@RequestMapping("/search/index")
public class SearchIndexController {

    private final SearchEngineClient engineClient;
    private final SearchProperties properties;

    public SearchIndexController(SearchEngineClient engineClient, SearchProperties properties) {
        this.engineClient = engineClient;
        this.properties = properties;
    }

    /** 索引清单（引擎真实视图，区别于 /search/stats/index 的按已启用源水位） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:index:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:index:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        boolean engineUp = engineClient.ping();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engineUp", engineUp);
        result.put("indexPrefix", properties.getIndexPrefix().toLowerCase());
        result.put("indices", engineUp ? engineClient.listIndices(listPattern()) : List.of());
        return Result.ok(result);
    }

    /** 浏览索引文档（keyword 空 = 全量分页；非空按 title/content 过滤） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:index:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:index:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/docs")
    public Result<Map<String, Object>> docs(@RequestParam String indexName,
                                            @RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String keyword) {
        checkIndexName(indexName);
        Map<String, Object> query = (keyword == null || keyword.isBlank())
                ? Map.of("match_all", Map.of())
                : Map.of("multi_match", Map.of("query", keyword, "fields", List.of("title", "content")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("from", Math.max(0, (pageNum - 1) * pageSize));
        body.put("size", Math.min(Math.max(pageSize, 1), 200));
        body.put("track_total_hits", true);
        return Result.ok(engineClient.search(List.of(indexName), body));
    }

    /** 删除索引（结构 + 数据全删；源仍在时随后可经任务中心 full_rebuild 重建） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:index:delete", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:index:delete", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @DeleteMapping("/{indexName}")
    public Result<Void> delete(@PathVariable String indexName) {
        checkIndexName(indexName);
        if (!engineClient.deleteIndex(indexName)) {
            throw new ServiceException(404, "索引不存在: " + indexName);
        }
        return Result.ok();
    }

    /** 清空索引数据（保留索引结构与 mapping，文档清零），返回删除条数 */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:index:delete", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:index:delete", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/{indexName}/clear")
    public Result<Long> clear(@PathVariable String indexName) {
        checkIndexName(indexName);
        return Result.ok(engineClient.deleteByQuery(indexName, Map.of("match_all", Map.of())));
    }

    /** 前缀 + 租户后缀白名单校验：仅允许操作会话租户的索引（防误删/越权操作其他应用或其他租户索引） */
    private void checkIndexName(String indexName) {
        String prefix = properties.getIndexPrefix().toLowerCase() + "-";
        String tenantSuffix = "-" + TenantContext.getRequiredTenantId();
        if (indexName == null || !indexName.toLowerCase().startsWith(prefix)
                || !indexName.toLowerCase().endsWith(tenantSuffix)) {
            throw new ServiceException(400, "仅允许操作本租户索引（" + prefix + "*"
                    + tenantSuffix + "）: " + indexName);
        }
    }

    private String listPattern() {
        return properties.getIndexPrefix().toLowerCase() + "-*-"
                + TenantContext.getRequiredTenantId();
    }
}
