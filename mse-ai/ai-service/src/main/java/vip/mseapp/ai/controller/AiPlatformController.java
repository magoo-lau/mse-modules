package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.api.tenant.RemoteTenantService;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.Result;

import java.util.List;

/**
 * AI 公共数据：租户下拉选项（模型圈定 / API Key 归属 / 用量筛选共用）。
 *
 * <p>租户会话仅返回本租户（页面不再提供跨租户选择，防伪造 tenantId 越权）；
 * 平台会话返回全量租户。
 */
@RestController
@RequestMapping("/ai/platform")
public class AiPlatformController {

    /** 租户校验走基座契约（单体本地直调/微服务 Dubbo），不直接依赖 service-platform 实现 */
    @RpcReference
    private RemoteTenantService remoteTenantService;

    /** 租户下拉选项（含停用租户，编辑回显不丢名）；三个页面任一权限即可读 */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:model:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:model:save", type = MseConstants.LOGIN_TYPE_PLATFORM),
            @SaCheckPermission(value = "ai:apikey:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:apikey:list", type = MseConstants.LOGIN_TYPE_PLATFORM),
            @SaCheckPermission(value = "ai:usage:platform", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:usage:platform", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/tenant-options")
    public Result<List<TenantOption>> tenantOptions() {
        Long ctx = TenantContext.getTenantId();
        if (ctx != null && ctx != 0L) {
            // 租户会话：仅本租户（固定选项，界面隐藏选择控件后仍需回显名称）
            vip.mseapp.api.tenant.dto.TenantDTO t = remoteTenantService.getTenantById(ctx);
            return Result.ok(t == null ? List.of()
                    : List.of(new TenantOption(t.getId(), t.getCode(), t.getName(), t.getStatus())));
        }
        return Result.ok(remoteTenantService.listTenants().stream()
                .map(t -> new TenantOption(t.getId(), t.getCode(), t.getName(), t.getStatus()))
                .toList());
    }

    /** 租户下拉选项（仅暴露选择所需字段） */
    public record TenantOption(Long id, String code, String name, Integer status) {
    }
}
