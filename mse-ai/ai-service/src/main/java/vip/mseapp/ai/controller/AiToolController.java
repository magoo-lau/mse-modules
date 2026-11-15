package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.ai.entity.AiTool;
import vip.mseapp.ai.entity.AiToolLog;
import vip.mseapp.ai.mapper.AiToolLogMapper;
import vip.mseapp.ai.service.AiToolService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;

import java.util.List;

/**
 * 工具中心（租户端页面；平台端入口已随菜单 460/464 转租户端下线，平台类型登录的
 * 放行分支仅为历史兼容）：租户端查询系统工具（只读可测试）并自建 http/dify 外部工具
 * （本租户自有行）；本地 Bean 对照与审计分页随权限点开放。
 */
@Validated
@RestController
@RequestMapping("/ai/tool")
public class AiToolController {

    private final AiToolService toolService;
    private final AiToolLogMapper toolLogMapper;

    public AiToolController(AiToolService toolService, AiToolLogMapper toolLogMapper) {
        this.toolService = toolService;
        this.toolLogMapper = toolLogMapper;
    }

    /** 工具列表（平台端=平台行；租户端=平台公共 + 本租户自有） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:list", type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @GetMapping("/page")
    public Result<PageResult<AiTool>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize,
                                           @RequestParam(required = false) String keyword) {
        Page<AiTool> page = toolService.page(pageNum, pageSize, keyword);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    /** 本地 Bean 工具描述（含内置），供登记时对照（平台端） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:list", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:list", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/local")
    public Result<List<AiToolDescriptor>> local() {
        return Result.ok(toolService.listLocalDescriptors());
    }

    /** 工具下拉选项（租户端智能体编辑 + 平台端公共智能体编辑共用，任一权限即可读；
     * 平台端用 ai:tool:list——原 ai:agent:platform 已随智能体租户化退役，不应再引用已删除的权限码） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:agent:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:list", type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @GetMapping("/options")
    public Result<List<AiToolService.ToolOption>> options() {
        return Result.ok(toolService.listOptions());
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @PostMapping
    public Result<Long> save(@RequestBody AiTool tool) {
        return Result.ok(toolService.save(tool));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        toolService.delete(id);
        return Result.ok();
    }

    /** 手动测试工具（联通性验证；租户端可测系统工具与本租户自有工具） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)
    })
    @PostMapping("/{toolCode}/test")
    public Result<AiToolResult> test(@PathVariable String toolCode,
                                     @RequestBody(required = false) ToolTestRequest request) {
        return Result.ok(toolService.testInvoke(toolCode,
                request == null ? "{}" : request.arguments(), TenantContext.getTenantId()));
    }

    /** 工具调用审计分页（平台侧；可按智能体/会话过滤，对话问题现场排查用） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "ai:tool:manage", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/log/page")
    public Result<PageResult<AiToolLog>> logs(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "20") long pageSize,
                                              @RequestParam(required = false) String toolCode,
                                              @RequestParam(required = false) String agentCode,
                                              @RequestParam(required = false) String sessionId) {
        LambdaQueryWrapper<AiToolLog> wrapper = new LambdaQueryWrapper<AiToolLog>()
                .eq(toolCode != null && !toolCode.isBlank(), AiToolLog::getToolCode, toolCode)
                .eq(agentCode != null && !agentCode.isBlank(), AiToolLog::getAgentCode, agentCode)
                .eq(sessionId != null && !sessionId.isBlank(), AiToolLog::getSessionId, sessionId)
                .orderByDesc(AiToolLog::getId);
        Page<AiToolLog> page = toolLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    public record ToolTestRequest(String arguments) {
    }
}
