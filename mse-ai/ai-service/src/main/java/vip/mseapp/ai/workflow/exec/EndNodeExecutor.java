package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结束节点：声明工作流的结构化输出（变量名 → 引用），
 * RPC / 开放 API 同步通道取此值作为返回（无 end 时回退 answer 文本）。
 */
@Component
public class EndNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "end";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        Map<String, Object> outputMap = new LinkedHashMap<>();
        node.map("outputs").forEach((name, ref) ->
                outputMap.put(name, ref instanceof String text
                        ? VarRenderer.resolve(text, ctx.resolver()) : ref));
        if (!outputMap.isEmpty()) {
            ctx.setOutput(node.id(), "output", outputMap);
            ctx.setFinalOutput(outputMap);
            return;
        }
        String template = node.str("output", "");
        String rendered = VarRenderer.render(template, ctx.resolver());
        ctx.setOutput(node.id(), "output", rendered);
        ctx.setFinalOutput(rendered);
    }
}
