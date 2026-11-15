package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.ai.entity.AiApiKey;
import vip.mseapp.ai.open.OpenApiKeyService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;

import java.util.Map;

/**
 * 开放 API 密钥管理（租户自管）：签发/启停/删除均按会话归属本租户，明文 Key 仅签发响应返回一次。
 */
@Validated
@RestController
@RequestMapping("/ai/apikey")
public class AiApiKeyController {

    private final OpenApiKeyService keyService;

    public AiApiKeyController(OpenApiKeyService keyService) {
        this.keyService = keyService;
    }

    @SaCheckPermission(value = "ai:apikey:create", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody @Validated ApiKeyCreateRequest request) {
        // 归属强制为会话租户（body 不再接受 tenantId）
        OpenApiKeyService.IssuedKey issued = keyService.create(TenantContext.getRequiredTenantId(),
                request.remark(), request.agentCodes(), request.ratePerMinute());
        // 明文 key 只出现在本次响应
        return Result.ok(Map.of("appId", issued.entity().getAppId(),
                "apiKey", issued.apiKey(),
                "id", issued.entity().getId()));
    }

    @SaCheckPermission(value = "ai:apikey:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<AiApiKey>> page(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "10") long pageSize) {
        Page<AiApiKey> page = keyService.page(pageNum, pageSize, TenantContext.getRequiredTenantId());
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckPermission(value = "ai:apikey:create", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/status")
    public Result<Void> status(@PathVariable Long id, @RequestBody @Validated StatusRequest request) {
        keyService.updateStatus(id, request.status(), TenantContext.getRequiredTenantId());
        return Result.ok();
    }

    @SaCheckPermission(value = "ai:apikey:create", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        keyService.delete(id, TenantContext.getRequiredTenantId());
        return Result.ok();
    }

    public record ApiKeyCreateRequest(String agentCodes, Integer ratePerMinute, String remark) {
    }

    public record StatusRequest(int status) {
    }
}
