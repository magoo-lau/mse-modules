package vip.mseapp.search.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckOr;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.search.service.SearchQueryService;
import vip.mseapp.core.result.Result;

import java.util.List;

/**
 * 搜索 API（基座首页卡片 / 顶栏全局搜索消费）：登录即可用（租户端与平台端双域），
 * 三层权限过滤由服务端强制注入——租户隔离、来源权限码、个人数据范围。
 */
@RestController
@RequestMapping("/search/api")
public class SearchApiController {

    private final SearchQueryService queryService;

    public SearchApiController(SearchQueryService queryService) {
        this.queryService = queryService;
    }

    @SaCheckOr(login = {
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @PostMapping("/query")
    public Result<SearchQueryService.SearchQueryResult> query(@RequestBody QueryRequest request) {
        return Result.ok(queryService.query(request.keyword(), request.sourceCodes(), request.domain(),
                request.pageNum() == null ? 1 : request.pageNum(),
                request.pageSize() == null ? 10 : request.pageSize(),
                false));
    }

    /** 域选项（已注册源的业务域去重，权限口径与 query 一致）：前端渲染「全部域 + 各域」chips */
    @SaCheckOr(login = {
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @GetMapping("/domains")
    public Result<List<String>> domains() {
        return Result.ok(queryService.visibleDomains());
    }

    @SaCheckOr(login = {
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @GetMapping("/suggest")
    public Result<List<String>> suggest(@RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "8") int limit) {
        return Result.ok(queryService.suggest(keyword, limit));
    }

    /** 展示字段元数据（可见源 × 平台配置 + 当前租户覆盖的 returned 字段）：搜索端据此渲染结果 extra 值 */
    @SaCheckOr(login = {
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckLogin(type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @GetMapping("/display-fields")
    public Result<List<SearchQueryService.SourceDisplayFields>> displayFields() {
        return Result.ok(queryService.displayFields());
    }

    public record QueryRequest(String keyword, List<String> sourceCodes, String domain, Long pageNum, Long pageSize) {
    }
}
