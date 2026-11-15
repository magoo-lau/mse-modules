package vip.mseapp.ai.workflow.exec;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.WorkflowContext;

/**
 * 开始节点：入参变量已由运行入口（FlowRunner）预置到 ctx，此处仅占位定序。
 */
@Component
public class StartNodeExecutor implements NodeExecutor {

    @Override
    public String type() {
        return "start";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        // no-op
    }
}
