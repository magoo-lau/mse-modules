package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mseapp.ai.avatar.AgentAvatarStorage;
import vip.mseapp.ai.entity.AiAgent;
import vip.mseapp.ai.service.AiAgentService;
import vip.mseapp.ai.service.AiChatService;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.FlowRunner;
import vip.mseapp.ai.workflow.WorkflowRunRecorder;
import vip.mseapp.ai.workflow.model.FlowGraph;
import vip.mseapp.ai.workflow.dsl.DifyDslImporter;
import vip.mseapp.ai.workflow.dsl.FlowDsl;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 智能体管理（租户自管）：/ai/agent 全部按会话世界单边读写——租户会话操作本租户自有行；
 * 智能体种子经每租户播种为可编辑副本，不再有"公共只读/复制为自有"模式。
 */
@Validated
@RestController
@RequestMapping("/ai/agent")
public class AiAgentController {

    private final AiAgentService agentService;

    private final FlowRunner flowRunner;

    private final vip.mseapp.ai.service.AiToolService toolService;

    private final WorkflowRunRecorder runRecorder;

    private final AgentAvatarStorage avatarStorage;

    private final vip.mseapp.ai.service.AiChatService chatService;

    public AiAgentController(AiAgentService agentService, FlowRunner flowRunner,
                             vip.mseapp.ai.service.AiToolService toolService, WorkflowRunRecorder runRecorder,
                             AgentAvatarStorage avatarStorage,
                             vip.mseapp.ai.service.AiChatService chatService) {
        this.agentService = agentService;
        this.flowRunner = flowRunner;
        this.toolService = toolService;
        this.runRecorder = runRecorder;
        this.avatarStorage = avatarStorage;
        this.chatService = chatService;
    }

    /** 本世界已发布智能体（对话工作台选择用） */
    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/visible")
    public Result<List<AiAgent>> visible() {
        return Result.ok(agentService.listVisible(TenantContext.getRequiredTenantId()));
    }

    /** 本世界智能体精简清单（code/name/描述，开放能力 API Key 白名单、用量页映射等跨页面下拉用；登录即可） */
    @SaCheckLogin(type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/options")
    public Result<List<AiAgent>> options() {
        return Result.ok(agentService.listVisible(TenantContext.getRequiredTenantId()));
    }

    @SaCheckPermission(value = "ai:agent:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<AiAgent>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String keyword) {
        Page<AiAgent> page = agentService.page(pageNum, pageSize, keyword);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckPermission(value = "ai:agent:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}")
    public Result<AiAgent> get(@PathVariable Long id) {
        return Result.ok(agentService.getVisibleById(id, TenantContext.getRequiredTenantId()));
    }

    @SaCheckPermission(value = "ai:agent:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> save(@RequestBody @Validated AgentSaveRequest request) {
        return Result.ok(agentService.save(toEntity(request), TenantContext.getRequiredTenantId()));
    }

    @SaCheckPermission(value = "ai:agent:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        agentService.delete(id, TenantContext.getRequiredTenantId());
        return Result.ok();
    }

    /** 头像上传（走 mse-fs 公开素材通道或本地磁盘兜底），返回可写入 ai_agent.avatar 的站内地址 */
    @SaCheckPermission(value = "ai:agent:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/avatar")
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Result.ok(avatarStorage.upload(file));
    }

    /** 头像读取（本地兜底实现专用；mse-fs 模式头像走 /fs/asset/**，不经此端点。img 不携带 token，免登录） */
    @GetMapping("/asset/{name}")
    public void asset(@PathVariable String name, HttpServletResponse response) throws IOException {
        avatarStorage.write(name, response);
    }

    /** 发布：草稿编排图校验后拷贝为运行版并置已发布（对话/RPC 即刻生效） */
    @SaCheckPermission(value = "ai:agent:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id) {
        agentService.publish(id, TenantContext.getRequiredTenantId());
        return Result.ok();
    }

    /** 编排画布「数据查询」节点数据源下拉：中台库 + 当前租户已启用数据源（数据服务工具通道不可用时空清单） */
    @SaCheckPermission(value = "ai:agent:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/datasource-options")
    public Result<List<Map<String, Object>>> datasourceOptions() {
        return Result.ok(toolService.listDatasourceOptions(TenantContext.getRequiredTenantId()));
    }

    @SaCheckPermission(value = "ai:agent:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/debug-run")
    public SseEmitter debugRun(@PathVariable Long id, @RequestBody @Validated DebugRunRequest request) {
        return debugStream(agentService.getVisibleById(id, TenantContext.getRequiredTenantId()), request);
    }

    /** DSL 导入（source=mse 自有格式 / dify 尽力映射），返回画布图与警告清单（不落库，前端确认后再保存） */
    @SaCheckPermission(value = "ai:agent:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/dsl/import")
    public Result<DslImportResult> dslImport(@PathVariable Long id,
                                             @RequestBody @Validated DslImportRequest request) {
        return Result.ok(dslImportGraph(request));
    }

    /** DSL 导出（mse-flow/v1 YAML） */
    @SaCheckPermission(value = "ai:agent:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/dsl/export")
    public Result<String> dslExport(@PathVariable Long id) {
        return Result.ok(dslExportYaml(agentService.getVisibleById(id, TenantContext.getRequiredTenantId())));
    }

    // ==================== 工作流运行历史 ====================

    /** 运行历史分页（本世界可见智能体的全部运行） */
    @SaCheckPermission(value = "ai:agent:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/run/page")
    public Result<PageResult<vip.mseapp.ai.entity.AiWorkflowRun>> runPage(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String agentCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel) {
        var page = runRecorder.page(pageNum, pageSize, agentCode, status, channel, false);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    /** 运行历史详情（含节点轨迹快照，时间线回放） */
    @SaCheckPermission(value = "ai:agent:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/run/{id}")
    public Result<vip.mseapp.ai.entity.AiWorkflowRun> runDetail(@PathVariable Long id) {
        return Result.ok(runRecorder.detail(id, false));
    }

    /**
     * 编排试运行 SSE 流：调试读草稿（发布前验证），无草稿回退运行版；
     * 简单配置智能体同样可调试——一次性转瞬执行（不建会话、不挂记忆），
     * tool 事件（逐步入参/耗时/超限）照常推送，调试台实时展示执行轨迹。
     */
    private SseEmitter debugStream(AiAgent agent, DebugRunRequest request) {
        if (!"flow".equals(agent.getOrchestrationType())) {
            AiChatService.ResolvedChat ctx = chatService.prepareDebug(agent,
                    TenantContext.getRequiredTenantId(), UserContext.getUserId());
            return chatService.stream(ctx, request.query(), request.inputs(), true);
        }
        FlowGraph graph = FlowGraph.parse(draftOrPublished(agent));
        if (graph == null) {
            throw new ServiceException(400, "该智能体尚未编排画布");
        }
        graph.requireValid();
        FlowRunEnv env = new FlowRunEnv()
                .tenantId(TenantContext.getRequiredTenantId())
                .userId(UserContext.getUserId())
                .channel("debug")
                .agentCode(agent.getAgentCode())
                .agentName(agent.getName())
                .sessionId(null)
                .defaultModelId(agent.getModelId());
        return flowRunner.stream(graph, request.query(), request.inputs(), env, null);
    }

    /** DSL 导入解析（source=mse 自有格式 / dify 尽力映射） */
    private DslImportResult dslImportGraph(DslImportRequest request) {
        FlowGraph graph;
        List<String> warnings;
        if ("dify".equals(request.source())) {
            var imported = DifyDslImporter.importDsl(request.content());
            graph = imported.graph();
            warnings = imported.warnings();
        } else {
            graph = FlowDsl.fromYaml(request.content());
            warnings = List.of();
        }
        graph.requireValid();
        return new DslImportResult(graph, warnings);
    }

    /** 编辑态编排图：草稿优先（编辑器所见即所测），无草稿回退运行版 */
    private static String draftOrPublished(AiAgent agent) {
        return agent.getDraftGraphJson() != null && !agent.getDraftGraphJson().isBlank()
                ? agent.getDraftGraphJson() : agent.getGraphJson();
    }

    private String dslExportYaml(AiAgent agent) {
        FlowGraph graph = FlowGraph.parse(draftOrPublished(agent));
        if (graph == null) {
            throw new ServiceException(400, "该智能体尚未编排画布");
        }
        return FlowDsl.toYaml(graph, agent.getName(), agent.getDescription());
    }

    private static AiAgent toEntity(AgentSaveRequest request) {
        AiAgent agent = new AiAgent();
        agent.setId(request.id());
        agent.setAgentCode(request.agentCode());
        agent.setName(request.name());
        agent.setAvatar(request.avatar());
        agent.setDescription(request.description());
        agent.setSystemPrompt(request.systemPrompt());
        agent.setModelId(request.modelId());
        agent.setTemperature(request.temperature());
        agent.setWelcomeMessage(request.welcomeMessage());
        agent.setToolCodes(request.toolCodes());
        agent.setKbIds(request.kbIds());
        agent.setOrchestrationType(request.orchestrationType());
        // 画布保存即草稿（发布走 /publish 显式动作）
        // 画布保存即草稿：graphJson 交由 service 统一落 draftGraphJson 并做 flow 画布校验
        agent.setGraphJson(request.graphJson());
        agent.setStatus(request.status());
        agent.setRemark(request.remark());
        return agent;
    }

    public record AgentSaveRequest(
            Long id,
            @NotBlank(message = "智能体编码不能为空") String agentCode,
            @NotBlank(message = "名称不能为空") String name,
            String avatar,
            String description,
            String systemPrompt,
            Long modelId,
            Double temperature,
            String welcomeMessage,
            String toolCodes,
            String kbIds,
            String orchestrationType,
            String graphJson,
            Integer status,
            String remark) {
    }

    public record DebugRunRequest(
            @NotBlank(message = "调试输入不能为空") String query,
            Map<String, String> inputs) {
    }

    public record DslImportRequest(
            @NotBlank(message = "DSL 内容不能为空") String content,
            String source) {
    }

    public record DslImportResult(FlowGraph graph, List<String> warnings) {
    }
}
