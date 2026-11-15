package vip.mseapp.ai.workflow.model;

/**
 * 节点执行轨迹（SSE node 事件与前端时间线数据源；MVP 不落库）。
 */
public class NodeTrace {

    public static final String RUNNING = "running";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    /** 用户主动停止 / SSE 断开联动取消（区别于 failed：不走 errorStrategy、不写记忆） */
    public static final String CANCELLED = "cancelled";

    private String nodeId;

    private String nodeType;

    private String title;

    private String status = RUNNING;

    private Long elapsedMs;

    private String output;

    private String error;

    public NodeTrace() {
    }

    public NodeTrace(String nodeId, String nodeType, String title) {
        this.nodeId = nodeId;
        this.nodeType = nodeType;
        this.title = title;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
