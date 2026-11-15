package vip.mseapp.search.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.search.api.SearchFieldDescriptor;
import vip.mseapp.search.entity.SearchTenantField;
import vip.mseapp.search.service.SearchFieldCatalogService;
import vip.mseapp.search.service.SearchTenantFieldService;

import java.util.List;

/**
 * 租户搜索字段配置（租户端）：在平台字段配置基线上做查询层覆盖（可搜/权重/高亮/展示/显示名），
 * 并可从业务字段目录新增检索字段（携带目录类型进入 mapping 并集，仅本租户检索生效）。
 * 覆盖只影响本租户的搜索行为，不能改字段类型；新增字段必须存在于业务目录且不得触碰系统保留字段。
 */
@Validated
@RestController
@RequestMapping("/search/tenant-field")
public class SearchTenantFieldController {

    private final SearchTenantFieldService tenantFieldService;

    public SearchTenantFieldController(SearchTenantFieldService tenantFieldService) {
        this.tenantFieldService = tenantFieldService;
    }

    @SaCheckPermission(value = "search:tenantconfig:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/sources")
    public Result<List<SearchTenantFieldService.SourceFieldsView>> sources() {
        return Result.ok(tenantFieldService.sourcesWithFields());
    }

    @SaCheckPermission(value = "search:tenantconfig:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/list")
    public Result<List<SearchTenantField>> list() {
        return Result.ok(tenantFieldService.list());
    }

    /** 业务字段目录（按需实时拉取业务 Provider，标注平台收录/本租户新增；目录不可用时降级返回原因） */
    @SaCheckPermission(value = "search:tenantconfig:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/catalog")
    public Result<SearchFieldCatalogService.CatalogView> catalog(@RequestParam String sourceCode) {
        return Result.ok(tenantFieldService.catalog(sourceCode));
    }

    @SaCheckPermission(value = "search:tenantconfig:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping("/{sourceCode}")
    public Result<Void> save(@PathVariable String sourceCode,
                             @RequestBody List<SearchFieldDescriptor> overrides) {
        tenantFieldService.save(sourceCode, overrides);
        return Result.ok();
    }

    @SaCheckPermission(value = "search:tenantconfig:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{sourceCode}")
    public Result<Void> reset(@PathVariable String sourceCode) {
        tenantFieldService.reset(sourceCode);
        return Result.ok();
    }
}
