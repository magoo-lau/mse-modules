package vip.mseapp.im.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.api.tenant.RemoteTenantService;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImProvisionService;

import java.util.List;
import java.util.Map;

/**
 * IM 开通平台（平台运营端）：为租户编排独立 OpenIM 实例并写入 PLATFORM_HOSTED 台账。
 * 需平台权限 im:provision:manage（平台超管 * 放行；普通平台管理员的权限点登记随平台侧菜单下期补齐）。
 */
@RestController
@RequestMapping("/im/provision")
public class ImProvisionController {

    private final ImProvisionService provisionService;

    /** 租户下拉数据源（开通页选择目标租户；经 api-tenant 契约消费基座清单，不直连平台库） */
    @RpcReference
    private RemoteTenantService remoteTenantService;

    public ImProvisionController(ImProvisionService provisionService) {
        this.provisionService = provisionService;
    }

    /** 租户下拉选项（sys_tenant 全量含停用，排除平台 0 号行；status 0 启用 1 停用） */
    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @GetMapping("/tenants")
    public Result<List<TenantOption>> tenants() {
        List<TenantOption> options = remoteTenantService.listTenants().stream()
                .filter(t -> t.getId() != null && t.getId() > 0)
                .map(t -> new TenantOption(t.getId(), t.getCode(), t.getName(), t.getStatus()))
                .toList();
        return Result.ok(options);
    }

    /** 租户下拉选项行 */
    public record TenantOption(Long id, String code, String name, Integer status) {
    }

    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @PostMapping("/{tenantId}")
    public Result<Map<String, Object>> provision(@PathVariable Long tenantId) {
        return Result.ok(provisionService.provision(tenantId));
    }

    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @PostMapping("/{tenantId}/stop")
    public Result<Void> stop(@PathVariable Long tenantId) {
        provisionService.stop(tenantId);
        return Result.ok();
    }

    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @PostMapping("/{tenantId}/start")
    public Result<Void> start(@PathVariable Long tenantId) {
        provisionService.start(tenantId);
        return Result.ok();
    }

    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @DeleteMapping("/{tenantId}")
    public Result<Void> destroy(@PathVariable Long tenantId) {
        provisionService.destroy(tenantId);
        return Result.ok();
    }

    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @GetMapping("/{tenantId}")
    public Result<Map<String, Object>> status(@PathVariable Long tenantId) {
        return Result.ok(provisionService.status(tenantId));
    }

    /** 升级：换 openim-server 镜像 tag 滚动重建（中间件不动） */
    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @org.springframework.web.bind.annotation.PostMapping("/{tenantId}/upgrade")
    public Result<Map<String, Object>> upgrade(@PathVariable Long tenantId,
                                               @org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        return Result.ok(provisionService.upgrade(tenantId, body == null ? null : body.get("imageTag")));
    }

    /** 备份：mongo archive + minio 数据卷 tar，返回文件路径 */
    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @org.springframework.web.bind.annotation.PostMapping("/{tenantId}/backup")
    public Result<Map<String, Object>> backup(@PathVariable Long tenantId) {
        return Result.ok(provisionService.backup(tenantId));
    }

    /** 该实例的 nginx 同域反代配置段（文本） */
    @SaCheckPermission(value = "im:provision:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    @org.springframework.web.bind.annotation.GetMapping("/{tenantId}/nginx")
    public Result<String> nginx(@PathVariable Long tenantId) {
        return Result.ok(provisionService.nginxConf(tenantId));
    }
}
