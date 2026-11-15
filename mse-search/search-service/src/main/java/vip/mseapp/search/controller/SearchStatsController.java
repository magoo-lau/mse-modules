package vip.mseapp.search.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.search.engine.EsIndexManager;
import vip.mseapp.search.engine.SearchEngineClient;
import vip.mseapp.search.entity.SearchHotKeyword;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.service.HotKeywordService;
import vip.mseapp.search.service.SearchSourceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索统计（平台端）：热词榜与各源索引水位/文档量。
 */
@RestController
@RequestMapping("/search/stats")
public class SearchStatsController {

    private final HotKeywordService hotKeywordService;
    private final SearchSourceService sourceService;
    private final EsIndexManager indexManager;
    private final SearchEngineClient engineClient;

    public SearchStatsController(HotKeywordService hotKeywordService, SearchSourceService sourceService,
                                 EsIndexManager indexManager, SearchEngineClient engineClient) {
        this.hotKeywordService = hotKeywordService;
        this.sourceService = sourceService;
        this.indexManager = indexManager;
        this.engineClient = engineClient;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:stats:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:stats:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/hot")
    public Result<PageResult<SearchHotKeyword>> hot(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String keyword) {
        Page<SearchHotKeyword> page = hotKeywordService.page(pageNum, pageSize, keyword);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "search:stats:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "search:stats:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/index")
    public Result<List<Map<String, Object>>> indexStats() {
        long tenantId = TenantContext.getRequiredTenantId();
        List<Map<String, Object>> result = new ArrayList<>();
        boolean engineUp = engineClient.ping();
        for (SearchSource source : sourceService.listEnabled()) {
            String indexName = indexManager.indexName(source.getSourceCode(), tenantId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceCode", source.getSourceCode());
            row.put("sourceName", source.getSourceName());
            row.put("indexName", indexName);
            row.put("docCount", engineUp ? engineClient.docCount(indexName) : -1);
            row.put("engineUp", engineUp);
            result.add(row);
        }
        return Result.ok(result);
    }
}
