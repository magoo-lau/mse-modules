package vip.mseapp.data.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.data.entity.DatApiApp;
import vip.mseapp.data.entity.DatApiLog;
import vip.mseapp.data.mapper.DatApiLogMapper;
import vip.mseapp.data.service.ApiAppService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 调用方应用与授权管理（租户端）。
 */
@Validated
@RestController
@RequestMapping("/data/apiapp")
public class ApiAppController {

    private final ApiAppService appService;
    private final DatApiLogMapper logMapper;

    public ApiAppController(ApiAppService appService, DatApiLogMapper logMapper) {
        this.appService = appService;
        this.logMapper = logMapper;
    }

    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<DatApiApp>> page(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer status) {
        return Result.ok(appService.page(pageNum, pageSize, keyword, status));
    }

    /** 创建应用：appSecret 明文仅此一次返回 */
    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<ApiAppService.SecretView> create(@RequestBody @Validated CreateRequest request) {
        return Result.ok(appService.create(request.name(), request.remark()));
    }

    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/secret/reset")
    public Result<ApiAppService.SecretView> resetSecret(@PathVariable Long id) {
        return Result.ok(appService.resetSecret(id));
    }

    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enable) {
        appService.changeStatus(id, enable);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        appService.delete(id);
        return Result.ok();
    }

    // ---------------- 授权 ----------------

    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/grants")
    public Result<List<ApiAppService.GrantView>> grants(@PathVariable Long id) {
        return Result.ok(appService.grants(id));
    }

    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/grants")
    public Result<ApiAppService.GrantResult> grant(@PathVariable Long id,
                                                   @RequestBody @Validated GrantRequest request) {
        return Result.ok(appService.grant(id, request.apiIds(), request.expireTime()));
    }

    @SaCheckPermission(value = "data:service:app", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}/grants/{apiId}")
    public Result<Void> revoke(@PathVariable Long id, @PathVariable Long apiId) {
        appService.revoke(id, apiId);
        return Result.ok();
    }

    // ---------------- 调用日志 ----------------

    @SaCheckPermission(value = "data:service:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/logs")
    public Result<PageResult<DatApiLog>> logs(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "10") long pageSize,
                                              @RequestParam(required = false) Long apiId,
                                              @RequestParam(required = false) Long appId,
                                              @RequestParam(required = false) String status) {
        LambdaQueryWrapper<DatApiLog> wrapper = new LambdaQueryWrapper<DatApiLog>()
                .eq(apiId != null, DatApiLog::getApiId, apiId)
                .eq(appId != null, DatApiLog::getAppId, appId)
                .eq(status != null && !status.isBlank(), DatApiLog::getStatus, status)
                .orderByDesc(DatApiLog::getId);
        Page<DatApiLog> page = logMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    public record CreateRequest(
            @NotBlank(message = "应用名称不能为空") String name,
            String remark) {
    }

    public record GrantRequest(
            @NotEmpty(message = "请选择要授权的 API") List<Long> apiIds,
            LocalDateTime expireTime) {
    }
}
