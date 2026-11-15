package vip.mseapp.ai.workflow.exec;

import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.WorkflowContext;

/**
 * 节点执行器 SPI：一个节点类型一个实现（Spring Bean，引擎按 type 路由）。
 *
 * <p>执行器无状态；运行期数据经 {@code ctx}（变量/分支/轨迹）与 {@code env}
 * （租户/模型默认值/流式回调）显式传递，可在任意线程执行。
 * 分支路由节点（condition/classifier/iteration 隐式）把出口写入 {@code ctx.setBranch}。
 */
public interface NodeExecutor {

    /** 对应 FlowNode.type */
    String type();

    void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env);
}
