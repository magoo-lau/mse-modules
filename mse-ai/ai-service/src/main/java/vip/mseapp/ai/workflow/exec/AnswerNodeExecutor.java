package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;

import java.util.List;

/**
 * 直接回复节点：渲染回复模板下发给客户端并登记为本次运行最终回答。
 *
 * <p>流式直通——模板恰为对已流式 LLM 节点的单一引用（{{llm1.text}}）时不再重发，
 * 避免增量 + 全文重复；含字面量混排的模板整段发送。
 */
@Component
public class AnswerNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "answer";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String content = node.str("content", "");
        String rendered = VarRenderer.render(content, ctx.resolver());
        ctx.setOutput(node.id(), "text", rendered);
        ctx.setFinalAnswer(rendered);
        if (!env.streaming() || content.isBlank()) {
            return;
        }
        List<String> refs = VarRenderer.refs(content);
        if (VarRenderer.isPureRef(content.trim()) && refs.size() == 1) {
            String owner = refs.get(0).split("\\.", 2)[0];
            if (ctx.isStreamed(owner)) {
                return;
            }
        }
        env.messageSink().accept(rendered);
    }
}
