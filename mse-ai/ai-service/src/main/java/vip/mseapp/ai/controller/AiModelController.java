package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.ai.entity.AiModel;
import vip.mseapp.ai.service.AiModelService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;

import java.util.List;

/**
 * 模型管理（租户自管）：各会话世界单边读写本世界自有模型行（租户会话=本租户，平台会话=平台世界）。
 * 存量平台公共行已经每租户播种副本（V20260926_02_ai__tenant_seed.sql），不再有全局共享行。
 */
@Validated
@RestController
@RequestMapping("/ai/model")
public class AiModelController {

    private final AiModelService modelService;

    public AiModelController(AiModelService modelService) {
        this.modelService = modelService;
    }

    /** 模型配置不回显密钥（apiKey 相关字段输出占位） */
    private static AiModel mask(AiModel model) {
        if (model.getApiKeyEnc() != null) {
            model.setApiKeyEnc("******");
        }
        if (model.getEmbeddingApiKeyEnc() != null) {
            model.setEmbeddingApiKeyEnc("******");
        }
        return model;
    }

    @SaCheckPermission(value = "ai:model:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<AiModel>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String modelType) {
        Page<AiModel> page = modelService.page(pageNum, pageSize, keyword, modelType);
        page.getRecords().forEach(AiModelController::mask);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckPermission(value = "ai:model:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}")
    public Result<AiModel> get(@PathVariable Long id) {
        return Result.ok(mask(modelService.getById(id)));
    }

    @SaCheckPermission(value = "ai:model:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> save(@RequestBody @org.springframework.validation.annotation.Validated ModelSaveRequest request) {
        AiModel form = new AiModel();
        form.setId(request.id());
        form.setName(request.name());
        form.setProvider(request.provider());
        form.setProtocol(request.protocol());
        form.setModelType(request.modelType());
        form.setIsDefault(request.isDefault());
        form.setBaseUrl(request.baseUrl());
        form.setChatModel(request.chatModel());
        form.setEmbeddingModel(request.embeddingModel());
        form.setTemperature(request.temperature());
        form.setTopP(request.topP());
        form.setMaxTokens(request.maxTokens());
        form.setFailoverModelId(request.failoverModelId());
        form.setStatus(request.status());
        form.setRemark(request.remark());
        return Result.ok(modelService.save(form, request.apiKey(), request.embeddingApiKey()));
    }

    @SaCheckPermission(value = "ai:model:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelService.delete(id);
        return Result.ok();
    }

    @SaCheckPermission(value = "ai:model:test", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/test")
    public Result<String> test(@PathVariable Long id) {
        return Result.ok(modelService.test(id));
    }

    /**
     * 可用模型列表（智能体编辑/画布/知识库绑定用）。
     * type=chat 仅对话模型（智能体/工作流绑定）；type=embedding 仅向量模型（知识库绑定）；type=rerank 仅重排模型（知识库精排绑定）；空 = 全量。
     * 返回本世界自有启用模型（租户会话=本租户行，平台会话=平台世界行）。
     */
    @SaCheckPermission(value = "ai:agent:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/available")
    public Result<List<AiModel>> available(@RequestParam(required = false) String type) {
        return Result.ok(modelService.listAvailableForTenant(TenantContext.getRequiredTenantId(), type));
    }

    /** 设为本世界默认向量模型（仅向量行，世界内唯一默认） */
    @SaCheckPermission(value = "ai:model:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/default-embedding")
    public Result<Void> setDefaultEmbedding(@PathVariable Long id) {
        modelService.setDefaultEmbedding(id);
        return Result.ok();
    }

    public record ModelSaveRequest(
            Long id,
            @NotBlank(message = "模型名称不能为空") String name,
            String provider,
            String protocol,
            String modelType,
            Integer isDefault,
            @NotBlank(message = "端点不能为空") String baseUrl,
            String apiKey,
            String chatModel,
            String embeddingModel,
            String embeddingApiKey,
            Double temperature,
            Double topP,
            Integer maxTokens,
            Long failoverModelId,
            Integer status,
            String remark) {
    }
}
