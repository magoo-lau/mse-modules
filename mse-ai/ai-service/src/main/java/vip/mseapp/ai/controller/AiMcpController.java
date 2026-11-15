package vip.mseapp.ai.controller;

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
import vip.mseapp.ai.entity.AiMcpServer;
import vip.mseapp.ai.mapper.AiMcpServerMapper;
import vip.mseapp.ai.mcpclient.McpToolRegistry;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;

import java.util.List;

/**
 * 外部 MCP Server 管理（租户自管）：各会话世界单边注册/启停/连通性测试（列出远端工具）。
 */
@Validated
@RestController
@RequestMapping("/ai/mcp")
public class AiMcpController {

    private final AiMcpServerMapper serverMapper;
    private final McpToolRegistry registry;

    public AiMcpController(AiMcpServerMapper serverMapper, McpToolRegistry registry) {
        this.serverMapper = serverMapper;
        this.registry = registry;
    }

    @SaCheckPermission(value = "ai:mcp:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<AiMcpServer>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        Long world = TenantContext.getRequiredTenantId();
        Page<AiMcpServer> page = TenantContext.runAs(world, () -> serverMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<AiMcpServer>()
                        .eq(AiMcpServer::getTenantId, world)
                        .orderByDesc(AiMcpServer::getId)));
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckPermission(value = "ai:mcp:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> save(@RequestBody AiMcpServer form) {
        AiMcpServer server = form.getId() == null ? new AiMcpServer() : requireOwned(form.getId());
        server.setName(form.getName());
        server.setEndpoint(form.getEndpoint());
        server.setSsePath(form.getSsePath());
        server.setTransport(form.getTransport() == null ? "sse" : form.getTransport());
        server.setStatus(form.getStatus() == null ? 0 : form.getStatus());
        server.setRemark(form.getRemark());
        if (server.getId() == null) {
            Long world = TenantContext.getRequiredTenantId();
            server.setTenantId(world);
            TenantContext.runAs(world, () -> serverMapper.insert(server));
        } else {
            TenantContext.runAs(server.getTenantId(), () -> serverMapper.updateById(server));
            registry.invalidate(server.getName());
        }
        return Result.ok(server.getId());
    }

    @SaCheckPermission(value = "ai:mcp:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        AiMcpServer server = requireOwned(id);
        registry.invalidate(server.getName());
        TenantContext.runAs(server.getTenantId(), () -> serverMapper.deleteById(id));
        return Result.ok();
    }

    /** 连通性测试：返回远端工具名列表 */
    @SaCheckPermission(value = "ai:mcp:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{name}/test")
    public Result<List<String>> test(@PathVariable String name) {
        return Result.ok(registry.listToolNames(name));
    }

    /** 本租户自有行（随世界路由读取 + 显式归属校验，兜底其他世界行） */
    private AiMcpServer requireOwned(Long id) {
        AiMcpServer server = TenantContext.runAs(TenantContext.getRequiredTenantId(), () ->
                serverMapper.selectById(id));
        if (server == null || !TenantContext.getRequiredTenantId().equals(server.getTenantId())) {
            throw new ServiceException(404, "MCP Server 不存在");
        }
        return server;
    }
}
