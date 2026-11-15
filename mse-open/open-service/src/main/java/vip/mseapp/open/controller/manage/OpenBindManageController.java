package vip.mseapp.open.controller.manage;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.entity.OpenUserBind;
import vip.mseapp.open.service.OpenBindService;

import java.util.List;

/**
 * 身份绑定检索（/openapp/bind/**，双端会话；绑定关系经开放 API 维护）。
 * 归属域随会话收口：租户会话锁定本租户（防跨租户读绑定/枚举他租户用户），平台会话跨租户。
 */
@RestController
@RequestMapping("/openapp/bind")
public class OpenBindManageController {

    private final OpenBindService bindService;

    public OpenBindManageController(OpenBindService bindService) {
        this.bindService = bindService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:bind:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:bind:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/page")
    public Result<PageResult<OpenUserBind>> page(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String sourceSystem) {
        return Result.ok(bindService.pageAll(pageNum, pageSize, resolveTenantId(tenantId), userId, sourceSystem));
    }

    /** 用户下拉选项（筛选条件；tenantId 决定用户域，租户会话锁定本租户，平台会话默认平台域 0） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:bind:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:bind:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/user-options")
    public Result<List<OpenBindService.UserOption>> userOptions(
            @RequestParam(name = "tenantId", defaultValue = "0") long tenantId) {
        Long resolved = resolveTenantId(tenantId);
        return Result.ok(bindService.listUserOptions(resolved == null ? 0L : resolved));
    }

    /** 来源系统下拉选项（绑定表已有值 distinct；租户会话仅本租户域） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:bind:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:bind:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/source-options")
    public Result<List<String>> sourceOptions() {
        return Result.ok(bindService.listSourceOptions(resolveTenantId(null)));
    }

    /**
     * 归属租户解析：租户会话一律锁定会话租户（请求传入的 tenantId 仅平台会话可指定，防跨租户枚举）；
     * 平台会话取请求值（可空 = 跨租户/平台域）。
     */
    private static Long resolveTenantId(Long requested) {
        Long ctx = TenantContext.getTenantId();
        if (ctx != null && ctx != 0L) {
            return ctx;
        }
        return requested;
    }
}
