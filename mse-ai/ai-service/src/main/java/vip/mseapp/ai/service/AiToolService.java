package vip.mseapp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.ai.entity.AiTool;
import vip.mseapp.ai.entity.AiToolLog;
import vip.mseapp.ai.mapper.AiToolLogMapper;
import vip.mseapp.ai.mapper.AiToolMapper;
import vip.mseapp.ai.mcpclient.McpToolRegistry;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具中心：把「本地 Bean / 远程 RPC 契约 / 内置 / 外部 HTTP / Dify」统一解析为
 * Spring AI {@link ToolCallback}，供智能体运行时挂载。
 *
 * <p>解析顺序：MCP 外部工具（编码 mcp:{server}:{tool}）→ ai_tool 登记行（登记行优先，
 * 停用即真正不可用；按 source 路由——builtin/local 容器内 Bean 直调、rpc 在 dubbo 模式
 * 经泛化调用（local 模式回落容器内同编码 Bean，单体聚合形态即各业务积木的实现）、
 * http/dify 走 {@link ExternalToolInvoker}）→ 未登记编码回落本地 Bean（兼容）。
 * 每次执行落 ai_tool_log 审计。
 *
 * <p>系统工具（builtin/local）由 {@code LocalToolAutoRegistrar} 启动扫描自动登记，
 * rpc 工具由提供方 ai-api 推送器自动登记，均无需手动维护、登记来源零 SQL 种子
 * （V20261012_01/_02 已退役历史引导行）；工具中心登记表单仅面向 http/dify 外部工具
 * （登记为租户自有行；页面为租户端，平台端入口已随菜单 460/464 转租户端下线）。
 *
 * <p>工具已真租户化（V20260926_04 播种副本）：各世界自有登记行随世界路由（tenant_id=世界编号，
 * 平台世界行 tenant_id=0 仅平台世界可见），读写统一 {@link TenantContext#runAs} 显式锚定世界——
 * 独立库租户落专属库、共享租户落绑定共享库、平台世界 0 落平台库/降级主库。
 * 解析顺序与审计口径不变（builtin/rpc 平台登记源的写入仍走 LocalToolAutoRegistrar /
 * RemoteAiToolRegistryImpl 的平台登记口径，见各自注释）；播种脚本只覆盖建租户一刻，
 * 之后平台侧新登记/内容演进经 {@link #ensureDeclaredTenantCopies(long)} 在读取入口
 * （工具中心分页 / 绑定下拉 / 对话解析）懒补齐租户副本，与「绑定工具」下拉清单保持一致。
 */
@Service
public class AiToolService {

    private static final Logger log = LoggerFactory.getLogger(AiToolService.class);

    /** 工具执行上下文（审计与租户归属；闭包捕获，不依赖 ThreadLocal） */
    public record ToolInvocationContext(String agentCode, String sessionId, Long tenantId) {
    }

    @FunctionalInterface
    public interface ToolInvoker {
        AiToolResult invoke(String argumentsJson);
    }

    private final ObjectProvider<RemoteAiTool> localTools;
    private final AiToolMapper toolMapper;
    private final AiToolLogMapper toolLogMapper;
    private final RpcToolInvoker rpcToolInvoker;
    private final ExternalToolInvoker externalToolInvoker;
    private final McpToolRegistry mcpToolRegistry;
    private final Environment environment;
    private final vip.mseapp.ai.workflow.FlowProperties flowProperties;

    /** 租户副本补齐节流（对话解析热路径）：world → 最近一次补齐时间戳，TTL 内跳过重复扫描 */
    private final Map<Long, Long> tenantEnsureMemo = new ConcurrentHashMap<>();
    private static final long TENANT_ENSURE_TTL_MS = 60_000;

    public AiToolService(ObjectProvider<RemoteAiTool> localTools, AiToolMapper toolMapper,
                         AiToolLogMapper toolLogMapper, RpcToolInvoker rpcToolInvoker,
                         ExternalToolInvoker externalToolInvoker, McpToolRegistry mcpToolRegistry,
                         Environment environment,
                         vip.mseapp.ai.workflow.FlowProperties flowProperties) {
        this.localTools = localTools;
        this.toolMapper = toolMapper;
        this.toolLogMapper = toolLogMapper;
        this.rpcToolInvoker = rpcToolInvoker;
        this.externalToolInvoker = externalToolInvoker;
        this.mcpToolRegistry = mcpToolRegistry;
        this.environment = environment;
        this.flowProperties = flowProperties;
    }

    /** 解析工具编码为 Spring AI 工具回调（未知编码跳过并告警，不阻断对话） */
    public List<ToolCallback> resolveCallbacks(Collection<String> toolCodes, ToolInvocationContext context) {
        return resolveCallbacks(toolCodes, context, null);
    }

    /** 解析工具编码为 Spring AI 工具回调（带执行观察者，对话链路实时上报工具事件） */
    public List<ToolCallback> resolveCallbacks(Collection<String> toolCodes, ToolInvocationContext context,
                                               ToolExecutionSupport.ToolExecutionListener listener) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (toolCodes == null || toolCodes.isEmpty()) {
            return callbacks;
        }
        if (context.tenantId() != null) {
            ensureDeclaredTenantCopiesThrottled(context.tenantId());
        }
        for (String toolCode : toolCodes) {
            try {
                // MCP 外部工具：约定编码 mcp:{serverName}:{toolName}
                if (toolCode.startsWith("mcp:")) {
                    String rest = toolCode.substring("mcp:".length());
                    int split = rest.indexOf(':');
                    if (split > 0) {
                        callbacks.add(observe(mcpToolRegistry.callback(rest.substring(0, split),
                                rest.substring(split + 1)), listener));
                    } else {
                        log.warn("[mse-ai] MCP 工具编码非法，跳过: {}", toolCode);
                    }
                    continue;
                }
                // 登记行优先（调用方世界自有行，副本化后同码行世界内唯一；停用即真正不可用），
                // 未登记回落容器内本地 Bean
                AiTool registered = selectVisibleTool(toolCode, context.tenantId());
                if (registered != null && registered.getStatus() != null && registered.getStatus() != 0) {
                    log.warn("[mse-ai] 工具已停用，跳过: {}", toolCode);
                    continue;
                }
                if (registered == null) {
                    RemoteAiTool local = findLocalTool(toolCode);
                    if (local != null) {
                        callbacks.add(buildCallback(local.describe(), local::invoke, context, listener));
                    } else {
                        log.warn("[mse-ai] 工具未注册，跳过: {}", toolCode);
                    }
                    continue;
                }
                ToolCallback callback = resolveRegistered(registered, context, listener);
                if (callback != null) {
                    callbacks.add(callback);
                }
            } catch (Exception e) {
                log.warn("[mse-ai] 工具解析失败，跳过: {} - {}", toolCode, e.getMessage());
            }
        }
        return callbacks;
    }

    /**
     * 取调用方世界内的工具登记行：单世界读取（随世界路由 + 显式世界条件），
     * 租户世界命中租户副本、平台会话/无上下文命中平台世界行。
     */
    private AiTool selectVisibleTool(String toolCode, Long tenantId) {
        long world = tenantId != null ? tenantId
                : (TenantContext.getTenantId() == null ? MseConstants.PLATFORM_TENANT_ID : TenantContext.getTenantId());
        return TenantContext.runAs(world, () -> toolMapper.selectOne(new LambdaQueryWrapper<AiTool>()
                .eq(AiTool::getToolCode, toolCode)
                .eq(AiTool::getTenantId, world)
                .last("LIMIT 1")));
    }

    /** 启用态且当前世界可见的登记行：单世界读取（平台端=平台世界行；租户端=本租户副本行） */
    private List<AiTool> selectEnabledVisible() {
        long world = TenantContext.isPlatform() ? MseConstants.PLATFORM_TENANT_ID
                : TenantContext.getRequiredTenantId();
        return TenantContext.runAs(world, () -> toolMapper.selectList(new LambdaQueryWrapper<AiTool>()
                .eq(AiTool::getTenantId, world)
                .eq(AiTool::getStatus, 0)
                .orderByAsc(AiTool::getToolCode)));
    }

    /** 按登记来源路由工具执行通道（local 模式下 rpc 工具回落容器内同编码 Bean） */
    private ToolCallback resolveRegistered(AiTool tool, ToolInvocationContext context,
                                           ToolExecutionSupport.ToolExecutionListener listener) {
        String source = tool.getSource() == null ? "" : tool.getSource();
        switch (source) {
            case "local", "builtin": {
                RemoteAiTool local = findLocalTool(tool.getToolCode());
                if (local == null) {
                    log.warn("[mse-ai] 本地工具 {} 未在容器中找到（确认对应积木已引入/聚合），跳过",
                            tool.getToolCode());
                    return null;
                }
                return buildCallback(toDescriptor(tool), local::invoke, context, listener);
            }
            case "rpc": {
                if (isDubboMode()) {
                    String group = tool.getRpcGroup();
                    return buildCallback(toDescriptor(tool),
                            args -> rpcToolInvoker.invoke(group, tool.getToolCode(), args), context, listener);
                }
                RemoteAiTool local = findLocalTool(tool.getToolCode());
                if (local != null) {
                    return buildCallback(toDescriptor(tool), local::invoke, context, listener);
                }
                log.warn("[mse-ai] rpc 工具 {} 在单体模式不可用（提供方积木未聚合），跳过", tool.getToolCode());
                return null;
            }
            case "http", "dify":
                return buildCallback(toDescriptor(tool), args -> externalToolInvoker.invoke(tool, args),
                        context, listener);
            default:
                log.warn("[mse-ai] 工具来源 {} 暂不可用，跳过: {}", source, tool.getToolCode());
                return null;
        }
    }

    /** 手动测试调用（管理端调试工具联通性，测试不受启停限制），同样落审计 */
    public AiToolResult testInvoke(String toolCode, String argumentsJson, Long tenantId) {
        ToolInvocationContext context = new ToolInvocationContext("manual-test", null, tenantId);
        // MCP 外部工具不落 ai_tool 登记行，按约定编码 mcp:{server}:{tool} 直连执行
        if (toolCode != null && toolCode.startsWith("mcp:")) {
            String rest = toolCode.substring("mcp:".length());
            int split = rest.indexOf(':');
            if (split <= 0) {
                throw new ServiceException(400, "MCP 工具编码非法: " + toolCode);
            }
            ToolCallback mcpCallback = mcpToolRegistry.callback(rest.substring(0, split), rest.substring(split + 1));
            return executeAndLog(args -> {
                try {
                    return AiToolResult.ok(mcpCallback.call(args));
                } catch (Exception e) {
                    return AiToolResult.fail(e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                }
            }, toolCode, argumentsJson, context, null);
        }
        AiTool registered = selectVisibleTool(toolCode, tenantId);
        if (registered == null) {
            RemoteAiTool local = findLocalTool(toolCode);
            if (local == null) {
                throw new ServiceException(404, "工具未注册: " + toolCode);
            }
            return executeAndLog(local::invoke, toolCode, argumentsJson, context, null);
        }
        String source = registered.getSource() == null ? "" : registered.getSource();
        switch (source) {
            case "local", "builtin": {
                RemoteAiTool local = findLocalTool(toolCode);
                if (local == null) {
                    throw new ServiceException(400, "本地工具未在容器中找到（确认对应积木已引入/聚合）: " + toolCode);
                }
                return executeAndLog(local::invoke, toolCode, argumentsJson, context, null);
            }
            case "rpc": {
                if (!isDubboMode()) {
                    RemoteAiTool local = findLocalTool(toolCode);
                    if (local != null) {
                        return executeAndLog(local::invoke, toolCode, argumentsJson, context, null);
                    }
                    throw new ServiceException(400, "rpc 工具在单体模式不可用（提供方积木未聚合）: " + toolCode);
                }
                return executeAndLog(args -> rpcToolInvoker.invoke(registered.getRpcGroup(), toolCode, args),
                        toolCode, argumentsJson, context, null);
            }
            case "http", "dify":
                return executeAndLog(args -> externalToolInvoker.invoke(registered, args),
                        toolCode, argumentsJson, context, null);
            default:
                throw new ServiceException(400, "工具来源不支持测试: " + source);
        }
    }

    /**
     * 注册/更新工具登记（rpc 手工补登与 http/dify 外部工具；builtin/local 由启动扫描自动维护）。
     * 平台端登记为平台公共行（tenant_id=0）；租户端仅 http/dify，登记为租户自有行。
     *
     * <p>内容字段归属：builtin/local 与编辑态 rpc 行的内容（source/name/description/schema）
     * 由提供方代码权威维护（启动扫描/推送会覆盖），保存时以库内为准归一化，仅启停/备注/提供方
     * 服务名接受表单值——避免 API 直调制造「改了又被冲掉」的假编辑；rpc 新建补登与 http/dify
     * 全字段可编辑。
     */
    public Long save(AiTool form) {
        if (form.getToolCode() == null || form.getToolCode().isBlank()) {
            throw new ServiceException(400, "toolCode 不能为空");
        }
        String source = form.getSource() == null || form.getSource().isBlank() ? "rpc" : form.getSource();
        if (!List.of("rpc", "local", "builtin", "http", "dify").contains(source)) {
            throw new ServiceException(400, "工具来源不支持: " + source);
        }
        boolean platform = TenantContext.isPlatform();
        if (!platform && !List.of("http", "dify").contains(source)) {
            // rpc 指向内部 dubbo 泛化调用，不对租户开放
            throw new ServiceException(400, "租户端仅支持登记 HTTP/Dify 外部工具");
        }
        switch (source) {
            case "rpc" -> {
                if (form.getRpcGroup() == null || form.getRpcGroup().isBlank()) {
                    throw new ServiceException(400, "rpc 工具需填写提供方服务名");
                }
            }
            case "http" -> {
                JsonNode config = AiJson.tryParse(form.getConfigJson());
                String url = config == null ? "" : config.path("url").asText("");
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    throw new ServiceException(400, "http 工具需在连接配置中填写合法 url（http(s):// 开头）");
                }
            }
            case "dify" -> {
                JsonNode config = AiJson.tryParse(form.getConfigJson());
                if (config == null || config.path("apiKey").asText("").isBlank()) {
                    throw new ServiceException(400, "dify 工具需在连接配置中填写 apiKey");
                }
            }
            default -> {
                // builtin/local：描述字段由启动扫描同步，允许手工修正备注/启停
            }
        }
        AiTool tool;
        boolean providerOwned;
        long world = platform ? MseConstants.PLATFORM_TENANT_ID : TenantContext.getRequiredTenantId();
        if (form.getId() == null) {
            tool = new AiTool();
            providerOwned = "builtin".equals(source) || "local".equals(source);
        } else {
            // 单世界读取（随世界路由），归属校验世界等值
            tool = TenantContext.runAs(world, () -> toolMapper.selectById(form.getId()));
            if (tool == null) {
                throw new ServiceException(404, "工具不存在");
            }
            Long rowTenant = tool.getTenantId();
            if (rowTenant == null || rowTenant != world) {
                throw new ServiceException(403, "无权修改该工具登记");
            }
            // 登记行内容由提供方维护（builtin/local 启动扫描、rpc 推送同步）：
            // source/name/description/schema 以库内为准，仅启停/备注/提供方服务名接受表单值
            providerOwned = "builtin".equals(tool.getSource()) || "local".equals(tool.getSource())
                    || "rpc".equals(tool.getSource());
            if (providerOwned) {
                source = tool.getSource();
                form.setSource(source);
                form.setName(tool.getName());
                form.setDescription(tool.getDescription());
                form.setParametersJsonSchema(tool.getParametersJsonSchema());
            }
        }
        if (!providerOwned) {
            validateModelFacingFields(form);
        }
        tool.setToolCode(form.getToolCode());
        tool.setName(form.getName());
        tool.setDescription(form.getDescription());
        tool.setParametersJsonSchema(form.getParametersJsonSchema() == null
                ? "{\"type\":\"object\",\"properties\":{}}" : form.getParametersJsonSchema());
        tool.setSource(source);
        tool.setRpcGroup(form.getRpcGroup());
        tool.setConfigJson(form.getConfigJson());
        tool.setStatus(form.getStatus() == null ? 0 : form.getStatus());
        tool.setRemark(form.getRemark());
        if (tool.getId() == null) {
            // toolCode 世界内唯一（uk 已迁移为 (tenant_id, tool_code, deleted)）：本世界查重
            Long count = TenantContext.runAs(world, () -> toolMapper.selectCount(new LambdaQueryWrapper<AiTool>()
                    .eq(AiTool::getToolCode, tool.getToolCode())
                    .eq(AiTool::getTenantId, world)));
            if (count != null && count > 0) {
                throw new ServiceException(400, "toolCode 已存在: " + tool.getToolCode());
            }
            tool.setTenantId(world);
            try {
                // 单世界写（随世界路由，世界由显式 tenant_id 承载）
                TenantContext.runAs(world, () -> toolMapper.insert(tool));
            } catch (DataIntegrityViolationException e) {
                // 查重窗口竞态兜底（唯一键 uk_tool_world_code）
                throw new ServiceException(400, "toolCode 已存在: " + tool.getToolCode());
            }
        } else {
            try {
                TenantContext.runAs(world, () -> toolMapper.updateById(tool));
            } catch (DataIntegrityViolationException e) {
                throw new ServiceException(400, "toolCode 已被其他工具占用: " + tool.getToolCode());
            }
        }
        return tool.getId();
    }

    /**
     * 可编辑来源（rpc 手工补登/http/dify）的模型可见性校验：功能说明与每个参数的说明必填——
     * 这两者是模型判断「何时调用、怎么传参」的唯一依据，缺失则工具对模型不可用。
     * builtin/local 与编辑态 rpc 由提供方代码/推送负责且已归一化为库内值，此处不拦
     * （编辑启停/备注不被旧 Schema 卡住）。
     */
    private void validateModelFacingFields(AiTool form) {
        if (form.getDescription() == null || form.getDescription().isBlank()) {
            throw new ServiceException(400, "功能说明必填（模型据此判断何时调用该工具）");
        }
        if (form.getParametersJsonSchema() == null || form.getParametersJsonSchema().isBlank()) {
            return; // 无 Schema 视为无参工具，落库时补默认空对象
        }
        JsonNode schema = AiJson.tryParse(form.getParametersJsonSchema());
        if (schema == null || !schema.isObject()) {
            throw new ServiceException(400,
                    "参数 Schema 必须是 JSON 对象（{\"type\":\"object\",\"properties\":{...}}）");
        }
        JsonNode properties = schema.path("properties");
        if (properties.isMissingNode()) {
            return;
        }
        if (!properties.isObject()) {
            throw new ServiceException(400, "参数 Schema 的 properties 必须是 JSON 对象");
        }
        List<String> missing = new ArrayList<>();
        properties.propertyNames().forEach(name -> {
            if (properties.path(name).path("description").asText("").isBlank()) {
                missing.add(name);
            }
        });
        if (!missing.isEmpty()) {
            throw new ServiceException(400, "参数缺少说明（模型无法识别参数用途）: " + String.join("、", missing));
        }
    }

    /**
     * 删除工具登记：单世界读取 + 世界归属校验（租户会话只能删本世界副本行，平台世界行提示停用；
     * 平台会话治理平台世界行）——读写同随世界路由。
     */
    public void delete(Long id) {
        long world = TenantContext.isPlatform() ? MseConstants.PLATFORM_TENANT_ID
                : TenantContext.getRequiredTenantId();
        AiTool tool = TenantContext.runAs(world, () -> toolMapper.selectById(id));
        if (tool == null) {
            throw new ServiceException(404, "工具不存在");
        }
        // 系统工具守卫：builtin=平台登记的提供方自有工具（种子退役后租户副本行 tenant_id=本租户，
        // 不能再按 tenant_id=0 判定——原守卫对副本行永不命中，租户可直删系统工具且懒补齐无来源不可自愈）
        if (!TenantContext.isPlatform() && "builtin".equals(tool.getSource())) {
            throw new ServiceException(400, "系统工具不可删除（如需下线请停用）");
        }
        Long rowTenant = tool.getTenantId();
        if (rowTenant == null || rowTenant != world) {
            throw new ServiceException(403, "无权删除该工具登记");
        }
        TenantContext.runAs(world, () -> toolMapper.deleteById(id));
    }

    /**
     * 工具登记分页：单世界 DB 分页（平台端=平台世界行；租户端=本租户副本行，查询前懒补齐
     * 平台登记副本，使清单与「绑定工具」下拉一致）。系统工具只读语义由控制器/前端与
     * save/delete 守卫保证。
     */
    public Page<AiTool> page(long pageNum, long pageSize, String keyword) {
        long world = TenantContext.isPlatform() ? MseConstants.PLATFORM_TENANT_ID
                : TenantContext.getRequiredTenantId();
        ensureDeclaredTenantCopies(world);
        return TenantContext.runAs(world, () -> toolMapper.selectPage(new Page<>(pageNum, pageSize),
                baseWrapper(keyword).eq(AiTool::getTenantId, world)));
    }

    private static LambdaQueryWrapper<AiTool> baseWrapper(String keyword) {
        LambdaQueryWrapper<AiTool> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(AiTool::getToolCode, keyword)
                    .or().like(AiTool::getName, keyword));
        }
        wrapper.orderByDesc(AiTool::getId);
        return wrapper;
    }

    /**
     * 平台工具登记行租户副本懒补齐：播种脚本只在建租户一刻快照复制，之后平台侧新登记
     * （启动扫描 / 提供方推送）的工具不会进既有租户世界——租户端工具中心会停在旧快照、
     * 与「绑定工具」三源合并清单不一致，微服务形态下租户绑定的 rpc 工具还会因缺租户
     * 登记行而解析失败。此处在读取入口把平台登记行补齐到当前世界：缺副本 → 插入；
     * 系统来源（builtin/local/rpc）副本内容漂移 → 以平台行为权威刷新内容字段（启停/备注
     * 保留——系统工具内容本就不可租户编辑，与 save 的 providerOwned 归一口径一致）；
     * http/dify 平台行副本仅插入不更新（租户自有外部工具可编辑，不跟随平台行）。
     * 幂等：同码跳过、并发撞 uk_tool_world_code 忽略；语句级路由、无事务（跨库红线）；
     * 整体容错非致命（失败只影响本次清单，下个入口重试）。
     */
    public void ensureDeclaredTenantCopies(long world) {
        if (world == MseConstants.PLATFORM_TENANT_ID) {
            return; // 平台世界即登记行本体，无需副本
        }
        try {
            List<AiTool> declared = TenantContext.runAs(MseConstants.PLATFORM_TENANT_ID,
                    () -> toolMapper.selectList(new LambdaQueryWrapper<AiTool>()
                            .eq(AiTool::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                            .orderByAsc(AiTool::getToolCode)));
            if (declared.isEmpty()) {
                return;
            }
            List<String> codes = declared.stream().map(AiTool::getToolCode).toList();
            Map<String, AiTool> existing = TenantContext.runAs(world, () -> toolMapper.selectList(
                            new LambdaQueryWrapper<AiTool>()
                                    .eq(AiTool::getTenantId, world)
                                    .in(AiTool::getToolCode, codes)))
                    .stream().collect(Collectors.toMap(AiTool::getToolCode, t -> t, (a, b) -> a));
            for (AiTool origin : declared) {
                AiTool copy = existing.get(origin.getToolCode());
                if (copy == null) {
                    try {
                        TenantContext.runAs(world, () -> {
                            toolMapper.insert(copyForTenant(origin, world));
                            return null;
                        });
                        log.info("[mse-ai] 平台工具租户副本补齐: {}（tenant={}）", origin.getToolCode(), world);
                    } catch (DataIntegrityViolationException e) {
                        log.debug("[mse-ai] 平台工具租户副本并发落败，跳过: {}（tenant={}）",
                                origin.getToolCode(), world);
                    }
                    continue;
                }
                if (!isSystemSource(origin) || !isSystemSource(copy)) {
                    continue;
                }
                boolean drifted = !Objects.equals(copy.getName(), origin.getName())
                        || !Objects.equals(copy.getDescription(), origin.getDescription())
                        || !Objects.equals(copy.getParametersJsonSchema(), origin.getParametersJsonSchema())
                        || !Objects.equals(copy.getSource(), origin.getSource())
                        || !Objects.equals(copy.getRpcGroup(), origin.getRpcGroup());
                if (!drifted) {
                    continue;
                }
                AiTool target = copy;
                TenantContext.runAs(world, () -> toolMapper.update(null, new LambdaUpdateWrapper<AiTool>()
                        .eq(AiTool::getId, target.getId())
                        .set(AiTool::getName, origin.getName())
                        .set(AiTool::getDescription, origin.getDescription())
                        .set(AiTool::getParametersJsonSchema, origin.getParametersJsonSchema())
                        .set(AiTool::getSource, origin.getSource())
                        .set(AiTool::getRpcGroup, origin.getRpcGroup())));
                log.info("[mse-ai] 平台工具租户副本内容刷新: {}（tenant={}）", origin.getToolCode(), world);
            }
        } catch (Exception e) {
            log.warn("[mse-ai] 平台工具租户副本补齐失败（不影响本次查询）: {}", e.getMessage());
        }
    }

    /** 对话解析热路径的补齐节流：每世界 TTL 内只扫描一次（ensure 整体容错，失败下轮重试） */
    private void ensureDeclaredTenantCopiesThrottled(long world) {
        if (world == MseConstants.PLATFORM_TENANT_ID) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = tenantEnsureMemo.get(world);
        if (last != null && now - last < TENANT_ENSURE_TTL_MS) {
            return;
        }
        ensureDeclaredTenantCopies(world);
        tenantEnsureMemo.put(world, now);
    }

    /** 平台登记行 → 租户副本：内容与启停状态照搬（播种脚本 V20261010_02 同口径），id 雪花新生成 */
    private AiTool copyForTenant(AiTool origin, long world) {
        AiTool copy = new AiTool();
        copy.setToolCode(origin.getToolCode());
        copy.setName(origin.getName());
        copy.setDescription(origin.getDescription());
        copy.setParametersJsonSchema(origin.getParametersJsonSchema());
        copy.setSource(origin.getSource());
        copy.setRpcGroup(origin.getRpcGroup());
        copy.setConfigJson(origin.getConfigJson());
        copy.setTenantId(world);
        copy.setStatus(origin.getStatus() == null ? 0 : origin.getStatus());
        copy.setRemark(origin.getRemark());
        return copy;
    }

    /** 系统来源（提供方权威维护内容）：builtin 随 ai-service / local 单体聚合 Bean / rpc 提供方推送 */
    private static boolean isSystemSource(AiTool tool) {
        String source = tool.getSource();
        return "builtin".equals(source) || "local".equals(source) || "rpc".equals(source);
    }

    /** 本地工具清单（内置 + 容器内全部 RemoteAiTool Bean），供管理端展示 */
    public List<AiToolDescriptor> listLocalDescriptors() {
        List<AiToolDescriptor> descriptors = new ArrayList<>();
        localTools.stream().forEach(tool -> descriptors.add(tool.describe()));
        return descriptors;
    }

    /**
     * 工具下拉选项（智能体/画布绑定用）：本地 Bean 工具（内置 + 各业务积木 RPC 契约）、
     * ai_tool 登记的启用工具（平台端=平台公共行，租户端=平台公共 + 本租户自有，查询前
     * 懒补齐平台登记副本）与 MCP 外部工具合并，按编码判重（本地优先，与运行时解析
     * 优先级一致）。携带 description/parametersJsonSchema 供绑定处展示用途与参数。
     */
    public List<ToolOption> listOptions() {
        ensureDeclaredTenantCopies(TenantContext.isPlatform() ? MseConstants.PLATFORM_TENANT_ID
                : TenantContext.getRequiredTenantId());
        Map<String, ToolOption> options = new LinkedHashMap<>();
        listLocalDescriptors().forEach(d ->
                options.put(d.getToolCode(), new ToolOption(d.getToolCode(), d.getName(), "local",
                        d.getDescription(), d.getParametersJsonSchema())));
        selectEnabledVisible().forEach(tool -> options.putIfAbsent(tool.getToolCode(),
                new ToolOption(tool.getToolCode(), tool.getName(), tool.getSource(),
                        tool.getDescription(), tool.getParametersJsonSchema())));
        try {
            mcpToolRegistry.listToolSummaries().forEach(s -> {
                String code = "mcp:" + s.serverName() + ":" + s.toolName();
                options.putIfAbsent(code, new ToolOption(code, s.toolName(), "mcp",
                        s.description(), s.inputSchema()));
            });
        } catch (Exception e) {
            log.warn("[mse-ai] MCP 工具选项合并失败，跳过: {}", e.getMessage());
        }
        return new ArrayList<>(options.values());
    }

    /** 工具下拉选项（绑定选择所需字段：编码/名称/来源 + 说明与参数 Schema） */
    public record ToolOption(String toolCode, String name, String source,
                             String description, String parametersJsonSchema) {
    }

    private RemoteAiTool findLocalTool(String toolCode) {
        for (RemoteAiTool tool : localTools) {
            if (toolCode.equals(tool.describe().getToolCode())) {
                return tool;
            }
        }
        return null;
    }

    private AiToolDescriptor toDescriptor(AiTool tool) {
        AiToolDescriptor descriptor = new AiToolDescriptor();
        descriptor.setToolCode(tool.getToolCode());
        descriptor.setName(tool.getName());
        descriptor.setDescription(tool.getDescription());
        descriptor.setParametersJsonSchema(tool.getParametersJsonSchema());
        return descriptor;
    }

    private ToolCallback buildCallback(AiToolDescriptor descriptor, ToolInvoker invoker,
                                       ToolInvocationContext context,
                                       ToolExecutionSupport.ToolExecutionListener listener) {
        String toolCode = descriptor.getToolCode();
        // OpenAI function 名规范仅允许字母数字下划线连字符：模型侧一律用 toolCode，
        // 中文名拼进 description 保留可读性（严格端点对非法名会直接拒绝或丢弃 tools）
        String displayName = descriptor.getName();
        String description = (displayName == null || displayName.isBlank() ? "" : displayName + "：")
                + (descriptor.getDescription() == null || descriptor.getDescription().isBlank()
                ? toolCode : descriptor.getDescription());
        ToolDefinition definition = ToolDefinition.builder()
                .name(toolCode)
                .description(description)
                .inputSchema(descriptor.getParametersJsonSchema() == null
                        ? "{\"type\":\"object\",\"properties\":{}}" : descriptor.getParametersJsonSchema())
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String argumentsJson) {
                if (listener != null) {
                    listener.onStart(toolCode, argumentsJson);
                }
                long start = System.currentTimeMillis();
                AiToolResult result = executeAndLog(invoker, toolCode, argumentsJson, context, listener);
                if (listener != null) {
                    listener.onComplete(toolCode, argumentsJson, result, System.currentTimeMillis() - start);
                }
                return result.getContent();
            }
        };
    }

    /** 为外部回调（MCP）挂上执行观察：异常转 fail 结果，listener 为空原样返回 */
    private static ToolCallback observe(ToolCallback delegate, ToolExecutionSupport.ToolExecutionListener listener) {
        if (listener == null) {
            return delegate;
        }
        String toolCode = delegate.getToolDefinition().name();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public String call(String argumentsJson) {
                listener.onStart(toolCode, argumentsJson);
                long start = System.currentTimeMillis();
                AiToolResult result;
                try {
                    result = AiToolResult.ok(delegate.call(argumentsJson));
                } catch (Exception e) {
                    result = AiToolResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
                listener.onComplete(toolCode, argumentsJson, result, System.currentTimeMillis() - start);
                return result.getContent();
            }
        };
    }

    private AiToolResult executeAndLog(ToolInvoker invoker, String toolCode, String argumentsJson,
                                       ToolInvocationContext context,
                                       ToolExecutionSupport.ToolExecutionListener listener) {
        long start = System.currentTimeMillis();
        // 机械短路（不依赖模型服从）：同工具累计空结果达阈值后已关闭，本对话内后续调用
        // 不再执行——「单条助手消息并行规划多个调用」时终止指令随结果返回、同批调用已
        // 全部执行完毕，指令式软停止拦不住同批调用；回调层闭包持有 recorder 跨批次持久，
        // 短路是唯一机械保证（真实环境实测 22 次换 keyword 调用全部穿透指令式停止）。
        boolean shortCircuited = listener instanceof ToolExecutionSupport.ToolExecutionRecorder rec
                && rec.isEmptyClosed(toolCode);
        AiToolResult result;
        if (shortCircuited) {
            result = AiToolResult.ok(FriendlyToolCallingManager.hardNoDataText(toolCode, "{\"closed\":true}"));
        } else {
            try {
                result = invoker.invoke(argumentsJson);
            } catch (Exception e) {
                result = AiToolResult.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        // 空结果终止（防御纵深，不依赖 ToolCallingManager 部署形态）：工具「成功 + 数据数组全空」
        // 对模型没有终止语义，会换 keyword/表名无限重试——在工具返回层直接包装终止指令
        // （指令置顶 + 原始返回内嵌），并累计空结果计数（达阈值机械关闭本工具）。
        // 改写层（FriendlyToolCallingManager）对包装后的内容不再命中空数据判定，不会双重叠加；
        // MCP 等未走本包装的通道由改写层兜底。
        // 审计日志保持记录工具原始返回（包装文本内嵌原始 JSON，排查以原始为准；短路调用记 0ms）。
        AiToolResult original = result;
        if (!shortCircuited && result.isSuccess()
                && FriendlyToolCallingManager.isEmptyDataResult(result.getContent())) {
            if (listener instanceof ToolExecutionSupport.ToolExecutionRecorder rec) {
                rec.markEmptyResult(toolCode);
            }
            result = AiToolResult.ok(FriendlyToolCallingManager.hardNoDataText(toolCode, result.getContent()));
        }
        long duration = System.currentTimeMillis() - start;
        Long tenantId = context.tenantId() != null ? context.tenantId()
                : TenantContext.getTenantId() == null ? 0L : TenantContext.getTenantId();
        try {
            AiToolLog audit = new AiToolLog();
            audit.setTenantId(tenantId);
            audit.setToolCode(toolCode);
            audit.setAgentCode(context.agentCode());
            audit.setSessionId(context.sessionId());
            audit.setArguments(argumentsJson == null || argumentsJson.length() <= 2000
                    ? argumentsJson : argumentsJson.substring(0, 2000));
            audit.setResult(original.getContent() == null || original.getContent().length() <= 2000
                    ? original.getContent() : original.getContent().substring(0, 2000));
            audit.setSuccess(original.isSuccess() ? 0 : 1);
            audit.setDurationMs(duration);
            if (!original.isSuccess()) {
                audit.setErrorMsg(original.getContent());
            }
            TenantContext.runAs(tenantId, () -> toolLogMapper.insert(audit));
        } catch (Exception e) {
            log.warn("[mse-ai] 工具审计写入失败: {}", toolCode, e);
        }
        return result;
    }

    /** 当前是否 Dubbo 模式（local 模式下 rpc 工具不可用） */
    public boolean isDubboMode() {
        return "dubbo".equalsIgnoreCase(environment.getProperty("mse.rpc.mode", "local"));
    }

    /**
     * 编排画布「数据查询」节点的数据源下拉选项：经数据查询工具通道的 datasource_list
     * 动作取「中台库 + 当前租户已启用数据源」。工具通道不可用（未部署数据服务/未登记
     * rpc 工具）时返回空清单，前端仅展示中台库兜底项。
     */
    public List<Map<String, Object>> listDatasourceOptions(Long tenantId) {
        List<ToolCallback> callbacks = resolveCallbacks(List.of(flowProperties.getDatasetToolCode()),
                new ToolInvocationContext("flow-editor", null, tenantId));
        if (callbacks.isEmpty() || tenantId == null) {
            return List.of();
        }
        String content = TenantContext.runAs(tenantId, () ->
                callbacks.get(0).call("{\"action\":\"datasource_list\"}"));
        if (content == null || content.isBlank()) {
            return List.of();
        }
        var json = vip.mseapp.ai.support.AiJson.tryParse(content);
        List<Map<String, Object>> options = new ArrayList<>();
        if (json != null && json.has("datasources")) {
            json.get("datasources").forEach(item -> {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", item.get("id").isNumber() ? item.get("id").numberValue() : item.get("id").asText());
                option.put("name", item.get("name") == null ? "" : item.get("name").asText());
                option.put("type", item.get("type") == null ? "" : item.get("type").asText());
                options.add(option);
            });
        }
        return options;
    }
}
