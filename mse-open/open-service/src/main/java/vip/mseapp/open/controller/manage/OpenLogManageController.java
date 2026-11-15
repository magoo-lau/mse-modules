package vip.mseapp.open.controller.manage;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.entity.OpenApiLog;
import vip.mseapp.open.service.OpenApiLogService;

import java.time.LocalDateTime;

/**
 * 开放 API 调用日志（/openapp/log/**，双端会话）。
 * 归属域随会话收口：租户会话锁定本租户（仅见本租户调用审计），平台会话跨租户全量。
 */
@RestController
@RequestMapping("/openapp/log")
public class OpenLogManageController {

    private final OpenApiLogService logService;

    public OpenLogManageController(OpenApiLogService logService) {
        this.logService = logService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:log:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:log:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/page")
    public Result<PageResult<OpenApiLog>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long appId,
            @RequestParam(required = false) String apiCode,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        // 归属租户解析：租户会话锁定会话租户，平台会话不限定（跨租户全量）
        Long ctx = TenantContext.getTenantId();
        Long tenantId = ctx != null && ctx != 0L ? ctx : null;
        return Result.ok(logService.page(pageNum, pageSize, tenantId, appId, apiCode, statusCode, beginTime, endTime));
    }
}
