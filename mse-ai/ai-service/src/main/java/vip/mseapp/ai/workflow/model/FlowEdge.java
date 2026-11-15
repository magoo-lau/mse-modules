package vip.mseapp.ai.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 画布连线：sourceHandle 为分支路由节点的出口标识（condition/classifier 的
 * {@code case0..caseN/default}），普通节点连线为 null。
 *
 * <p>访问器为 {@code source()} 风格，序列化依赖字段上的 {@code @JsonProperty}（见 FlowNode）。
 */
public class FlowEdge {

    @JsonProperty
    private String source;

    @JsonProperty
    private String target;

    @JsonProperty
    private String sourceHandle;

    public FlowEdge() {
    }

    public FlowEdge(String source, String target, String sourceHandle) {
        this.source = source;
        this.target = target;
        this.sourceHandle = sourceHandle;
    }

    public String source() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String target() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String sourceHandle() {
        return sourceHandle;
    }

    public void setSourceHandle(String sourceHandle) {
        this.sourceHandle = sourceHandle;
    }
}
