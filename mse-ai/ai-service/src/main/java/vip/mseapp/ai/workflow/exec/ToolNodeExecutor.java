package vip.mseapp.ai.workflow.exec;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.service.AiToolService;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具节点：调用工具中心的单个工具（内置/本地 Bean/RPC 泛化/MCP）。
 * 入参为"参数名 → 变量模板"映射，字符串值渲染后整体序列化为工具 JSON 入参。
 */
@Component
public class ToolNodeExecutor implements NodeExecutor {

    protected final AiToolService toolService;

    public ToolNodeExecutor(AiToolService toolService) {
        this.toolService = toolService;
    }

    @Override
    public String type() {
        return "tool";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String toolCode = node.str("toolCode", "");
        if (toolCode.isBlank()) {
            throw new ServiceException(400, "节点「" + node.title() + "」未配置工具编码");
        }
        List<ToolCallback> callbacks = toolService.resolveCallbacks(List.of(toolCode),
                new AiToolService.ToolInvocationContext(env.agentCode(), env.sessionId(), env.tenantId()));
        if (callbacks.isEmpty()) {
            throw new ServiceException(400, "工具未注册或已停用: " + toolCode);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        node.map("args").forEach((key, value) ->
                args.put(key, value instanceof String template ? VarRenderer.resolve(template, ctx.resolver()) : value));
        String result = invokeWithTenant(callbacks.get(0), AiJson.toJson(args), env);
        ctx.setOutput(node.id(), "text", result == null ? "" : result);
        Map<String, Object> json = tryParseMap(result);
        if (json != null) {
            ctx.setOutput(node.id(), "json", json);
        }
    }

    /**
     * 携带租户上下文调用工具：引擎线程池无 ThreadLocal 租户，本地工具的租户行级隔离
     * 与 Dubbo 泛化调用的 attachment 透传都依赖此处显式恢复。
     */
    protected String invokeWithTenant(ToolCallback callback, String argumentsJson, FlowRunEnv env) {
        return env.tenantId() == null
                ? callback.call(argumentsJson)
                : vip.mseapp.core.context.TenantContext.runAs(env.tenantId(), () -> callback.call(argumentsJson));
    }

    protected Map<String, Object> tryParseMap(String text) {
        var json = AiJson.tryParse(text);
        if (json != null && json.isObject()) {
            return AiJson.toMap(text);
        }
        // 兼容工具返回 {success,content} 包装：内容本身是 JSON 时解包
        if (json != null && json.has("content")) {
            var inner = AiJson.tryParse(json.get("content").asText());
            if (inner != null && inner.isObject()) {
                return AiJson.toMap(json.get("content").asText());
            }
        }
        return null;
    }
}
