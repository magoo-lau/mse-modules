package vip.mseapp.open.controller.manage;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.entity.OpenApiDef;
import vip.mseapp.open.security.OpenApiCatalogService;
import vip.mseapp.open.service.OpenApiRegistryService;

import java.util.List;

/**
 * 开放 API 目录管理（/openapp/api/**，双端会话）。
 *
 * <p>目录真租户化——每会话世界一份目录（租户会话读本租户目录行，平台会话读平台世界行）：
 * 查询与启停均为本世界行级语义（租户端启停只影响本租户，平台端治理平台域凭证的目录）。
 * 目录条目由两部分组成：SQL 种子内置（source=seed，open-service 精确端点承接，每租户播种副本）
 * 与业务积木自注册（source=rpc，扇出播种承接）。管理端只做治理——查询与启停：
 * 新增/定义变更由业务积木经 open-api 契约自动注册（重推自愈），管理端手工定义会被
 * 下次注册覆盖，故不提供手写 CRUD。
 */
@Validated
@RestController
@RequestMapping("/openapp/api")
public class OpenApiManageController {

    private final OpenApiCatalogService catalog;
    private final OpenApiRegistryService registryService;

    public OpenApiManageController(OpenApiCatalogService catalog, OpenApiRegistryService registryService) {
        this.catalog = catalog;
        this.registryService = registryService;
    }

    /** 全量目录（含停用，目录治理页展示 target/source） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:api:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:api:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/list")
    public Result<List<OpenApiDef>> list(@RequestParam(required = false) Integer status) {
        return Result.ok(catalog.listAll(status));
    }

    /** 启用中目录（应用授权弹窗：只允许勾选可用 API） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:api:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:api:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/enabled")
    public Result<List<OpenApiDef>> enabled() {
        return Result.ok(catalog.listEnabled());
    }

    /** 目录启停（本世界行级：停用即时生效，仅当前会话世界下线该 API） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "open:api:status", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "open:api:status", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id,
                                     @RequestBody @Validated StatusRequest request) {
        registryService.changeStatus(id, request.status());
        return Result.ok();
    }

    public record StatusRequest(@NotNull(message = "status 不能为空") Integer status) {
    }
}
