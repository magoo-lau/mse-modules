package vip.mseapp.ai.workflow.exec;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.service.AiToolService;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.FlowProperties;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据查询节点：面向数据中台的受控取数（默认复用 mse-data 的 {@code data_query} 工具通道——
 * ApiSqlGuard 只读校验 + 租户谓词注入 + 行数上限，安全模型与数据服务一致）。
 *
 * <p>输出 columns/rows/markdown/rowCount；markdown 为表格文本，供 LLM 节点直接阅读分析。
 */
@Component
public class DatasetNodeExecutor extends ToolNodeExecutor {

    private final FlowProperties properties;

    public DatasetNodeExecutor(AiToolService toolService, FlowProperties properties) {
        super(toolService);
        this.properties = properties;
    }

    @Override
    public String type() {
        return "dataset";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String sql = VarRenderer.render(node.str("sql", ""), ctx.resolver());
        if (sql == null || sql.isBlank()) {
            throw new ServiceException(400, "节点「" + node.title() + "」未配置查询 SQL");
        }
        String toolCode = node.str("toolCode", properties.getDatasetToolCode());
        int rowLimit = node.intg("rowLimit", properties.getDatasetRowLimit());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("action", "execute");
        args.put("sql", sql);
        Long datasourceId = node.lng("datasourceId", 0L);
        args.put("datasourceId", datasourceId);
        args.put("pageSize", rowLimit);
        // 复用工具中心通道（本地 Bean / rpc 登记工具均走此路径，审计随 ai_tool_log 落库）
        List<org.springframework.ai.tool.ToolCallback> callbacks = toolService.resolveCallbacks(
                List.of(toolCode),
                new AiToolService.ToolInvocationContext(env.agentCode(), env.sessionId(), env.tenantId()));
        if (callbacks.isEmpty()) {
            throw new ServiceException(400, "数据查询工具未注册: " + toolCode
                    + "（聚合形态需引入 mse-data 的 data-service 积木；微服务模式由 mse-data 自动推送登记，"
                    + "也可在工具中心手工登记 rpc 工具）");
        }
        String result = invokeWithTenant(callbacks.get(0), AiJson.toJson(args), env);
        parseResult(node, ctx, result);
        ctx.setOutput(node.id(), "text", result == null ? "" : result);
    }

    private void parseResult(FlowNode node, WorkflowContext ctx, String result) {
        JsonNode json = AiJson.tryParse(result);
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (json != null && json.has("columns") && json.has("rows")) {
            json.get("columns").forEach(column -> columns.add(column.asText()));
            json.get("rows").forEach(row -> {
                Map<String, Object> map = new LinkedHashMap<>();
                columns.forEach(column -> {
                    JsonNode cell = row.get(column);
                    map.put(column, cell == null || cell.isNull() ? null
                            : cell.isNumber() ? cell.numberValue() : cell.asText());
                });
                rows.add(map);
            });
        } else {
            // 非 data_query 形态的自定义查询工具：原样放行 text，markdown 退化
            ctx.setOutput(node.id(), "rowCount", 0);
            return;
        }
        ctx.setOutput(node.id(), "columns", columns);
        ctx.setOutput(node.id(), "rows", rows);
        ctx.setOutput(node.id(), "rowCount", rows.size());
        ctx.setOutput(node.id(), "markdown", toMarkdown(columns, rows));
    }

    private static String toMarkdown(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("| ");
        sb.append(String.join(" | ", columns)).append(" |\n| ");
        sb.append("| ".repeat(Math.max(0, columns.size()))).append("\n");
        for (Map<String, Object> row : rows) {
            sb.append("| ");
            List<String> cells = new ArrayList<>();
            for (String column : columns) {
                Object value = row.get(column);
                cells.add(value == null ? "" : String.valueOf(value));
            }
            sb.append(String.join(" | ", cells)).append(" |\n");
        }
        return sb.toString();
    }
}
