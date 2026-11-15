package vip.mseapp.open.controller.manage;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.entity.OpenApp;
import vip.mseapp.open.service.OpenAppService;
import vip.mseapp.web.log.OperLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 接入应用管理（/openapp/app/**，双端 Sa-Token 会话 + 权限点控制；租户会话归属锁定本租户）。
 *
 * <p>与开放 API（/open/api/**）完全隔离的两套端点：前者给人看，后者给机器调。
 */
@Validated
@RestController
@RequestMapping("/openapp/app")
public class OpenAppManageController {

    private final OpenAppService appService;

    public OpenAppManageController(OpenAppService appService) {
        this.appService = appService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/page")
    public Result<PageResult<OpenApp>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer status) {
        return Result.ok(appService.page(pageNum, pageSize, keyword, status));
    }

    /** 租户下拉选项（接入应用绑定租户；复用列表权限，无需租户管理权限） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/tenant-options")
    public Result<List<OpenAppService.TenantOption>> tenantOptions() {
        Long ctx = TenantContext.getTenantId();
        if (ctx != null && ctx != 0L) {
            // 租户会话：仅本租户（页面隐藏选择控件后仍需回显名称）
            return Result.ok(appService.listTenantOptions().stream()
                    .filter(t -> String.valueOf(t.id()).equals(String.valueOf(ctx)))
                    .toList());
        }
        return Result.ok(appService.listTenantOptions());
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/{id}")
    public Result<OpenApp> get(@PathVariable Long id) {
        return Result.ok(appService.get(id));
    }

    @OperLog(module = "open:app", type = "新增")
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:create", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:create", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping
    public Result<OpenAppService.AppCreated> create(@RequestBody @Validated AppCreateRequest request) {
        return Result.ok(appService.create(request.name(), resolveTenantId(request.tenantId()),
                request.rateLimitQps(), request.expireTime(), request.remark(),
                request.ipWhitelist(), request.domainWhitelist()));
    }

    @OperLog(module = "open:app", type = "修改")
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:update", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:update", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping
    public Result<Void> update(@RequestBody @Validated AppUpdateRequest request) {
        appService.update(request.id(), request.name(), resolveTenantId(request.tenantId()),
                request.rateLimitQps(), request.expireTime(), request.remark(),
                request.signRequired(), request.ipWhitelist(), request.domainWhitelist());
        return Result.ok();
    }

    @OperLog(module = "open:app", type = "启停")
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:change-status", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:change-status", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enable) {
        appService.changeStatus(id, enable);
        return Result.ok();
    }

    /** 重置密钥：明文仅本次返回 */
    @OperLog(module = "open:app", type = "重置密钥")
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:reset-secret", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:reset-secret", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping("/{id}/secret")
    public Result<SecretReset> resetSecret(@PathVariable Long id) {
        return Result.ok(new SecretReset(appService.resetSecret(id)));
    }

    @OperLog(module = "open:app", type = "删除")
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:delete", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:delete", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        appService.delete(id);
        return Result.ok();
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/{id}/api-ids")
    public Result<List<Long>> apiIds(@PathVariable Long id) {
        return Result.ok(appService.listApiIds(id));
    }

    /** 全量覆盖式 API 授权 */
    @OperLog(module = "open:app", type = "API 授权")
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:app:grant", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:app:grant", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping("/{id}/api-ids")
    public Result<Void> grantApis(@PathVariable Long id, @RequestBody ApiIdsRequest request) {
        appService.grantApis(id, request.apiIds());
        return Result.ok();
    }

    // -------------------- 请求体 --------------------

    /**
     * 归属租户解析：租户会话一律锁定会话租户（请求传入的 tenantId 仅平台会话可指定，防伪造越权）；
     * 平台会话取请求值（缺省平台域 0）。
     */
    private static Long resolveTenantId(Long requested) {
        Long ctx = TenantContext.getTenantId();
        if (ctx != null && ctx != 0L) {
            return ctx;
        }
        return requested == null ? 0L : requested;
    }

    public record AppCreateRequest(
            @NotBlank(message = "应用名称不能为空") String name,
            @NotNull(message = "绑定租户不能为空（平台域传 0）") Long tenantId,
            Long rateLimitQps,
            LocalDateTime expireTime,
            String remark,
            /** IP 白名单（逗号分隔，支持 IPv4 CIDR）；空 = 不限制 */
            String ipWhitelist,
            /** 域名白名单（逗号分隔，裸域名或 *.example.com 子域通配）；空 = 不限制 */
            String domainWhitelist) {
    }

    public record AppUpdateRequest(
            @NotNull(message = "应用编号不能为空") Long id,
            String name,
            Long tenantId,
            Long rateLimitQps,
            LocalDateTime expireTime,
            String remark,
            /** 强制请求签名验签：null 不修改，0/1 生效 */
            Integer signRequired,
            /** IP 白名单（逗号分隔，支持 IPv4 CIDR）；null 不修改，空串清空 */
            String ipWhitelist,
            /** 域名白名单（逗号分隔，裸域名或 *.example.com 子域通配）；null 不修改，空串清空 */
            String domainWhitelist) {
    }

    public record ApiIdsRequest(List<Long> apiIds) {
    }

    public record SecretReset(String appSecret) {
    }
}
