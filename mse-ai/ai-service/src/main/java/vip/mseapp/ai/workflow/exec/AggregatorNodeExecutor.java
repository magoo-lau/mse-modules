package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;

import java.util.List;

/**
 * 变量聚合节点：按声明顺序取首个非空变量作为 output（多分支结果归一）。
 */
@Component
public class AggregatorNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "aggregator";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        List<Object> variables = node.list("variables");
        for (Object item : variables) {
            String ref = String.valueOf(item);
            if (VarRenderer.isPureRef("{{" + ref + "}}")) {
                Object value = ctx.get(ref);
                if (value != null && !VarRenderer.display(value).isBlank()) {
                    ctx.setOutput(node.id(), "output", value);
                    return;
                }
            }
        }
        ctx.setOutput(node.id(), "output", "");
    }
}
