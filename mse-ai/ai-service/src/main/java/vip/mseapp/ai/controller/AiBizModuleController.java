package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.ai.entity.AiBizModule;
import vip.mseapp.ai.service.AiBizModuleService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;

import java.util.List;

/**
 * 业务模块注册（管理端只读 + 启停）：通用业务分析入口（biz_query）的路由目录。
 * 模块行由业务积木 AiBizModuleProvider 声明登记（local/rpc，提供方权威），管理侧仅可
 * 启停与备注，不做手工新增。
 */
@Validated
@RestController
@RequestMapping("/ai/biz-module")
public class AiBizModuleController {

    private final AiBizModuleService moduleService;
    private final vip.mseapp.ai.mapper.AiBizModuleMapper moduleMapper;

    public AiBizModuleController(AiBizModuleService moduleService,
                                 vip.mseapp.ai.mapper.AiBizModuleMapper moduleMapper) {
        this.moduleService = moduleService;
        this.moduleMapper = moduleMapper;
    }

    /** 模块目录列表（含停用，全局目录） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:list", type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @GetMapping("/list")
    public Result<List<AiBizModule>> list(@RequestParam(required = false) String keyword) {
        List<AiBizModule> modules = moduleService.listAll();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            modules = modules.stream()
                    .filter(m -> (m.getModuleCode() != null && m.getModuleCode().contains(kw))
                            || (m.getName() != null && m.getName().contains(kw)))
                    .toList();
        }
        return Result.ok(modules);
    }

    /** 启停模块（停用后 biz_query 不再路由该模块；声明登记不覆盖启停状态） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @PostMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        AiBizModule module = moduleMapper.selectById(id);
        if (module == null || module.getTenantId() == null || module.getTenantId() != 0L) {
            return Result.fail(404, "业务模块不存在: " + id);
        }
        module.setStatus(status != null && status == 0 ? 0 : 1);
        moduleMapper.updateById(module);
        return Result.ok();
    }
}
