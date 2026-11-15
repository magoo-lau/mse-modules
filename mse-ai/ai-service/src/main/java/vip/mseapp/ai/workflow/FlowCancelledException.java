package vip.mseapp.ai.workflow;

/**
 * 编排被取消（用户主动停止 / SSE 断开联动）。区别于节点执行失败：
 * 不走节点 errorStrategy、轨迹记 cancelled 而非 failed、不写会话记忆。
 */
public class FlowCancelledException extends RuntimeException {

    public FlowCancelledException() {
        super("编排已被取消");
    }
}
