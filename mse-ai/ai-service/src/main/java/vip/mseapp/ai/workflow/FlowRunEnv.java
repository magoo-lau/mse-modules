package vip.mseapp.ai.workflow;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 单次工作流执行的运行环境（显式携带，不依赖 ThreadLocal；可在任意线程执行）。
 * sink 回调实现方负责线程安全（SSE 发送同步）。
 */
public class FlowRunEnv {

    /** 协作式取消标志：停止接口/SSE 断开联动置位，引擎与执行器在检查点轮询（迭代子图共享同一实例） */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private Long tenantId;

    private Long userId;

    private String agentCode;

    /** 智能体名称快照（运行历史存档展示用） */
    private String agentName;

    /** 会话号；null = 无记忆（debug/RPC 无状态） */
    private String sessionId;

    private String channel = "gateway";

    /** 智能体默认模型（节点未指定 modelId 时回退） */
    private Long defaultModelId;

    private boolean streaming;

    /** 增量文本回调（message 事件） */
    private Consumer<String> messageSink = delta -> {
    };

    /** 引擎事件回调（"node" 等事件名 + JSON 载荷） */
    private BiConsumer<String, String> eventSink = (event, json) -> {
    };

    /** 迭代子图事件 id 前缀（如 iter1/） */
    private String nodeIdPrefix = "";

    public Long tenantId() {
        return tenantId;
    }

    public FlowRunEnv tenantId(Long tenantId) {
        this.tenantId = tenantId;
        return this;
    }

    public Long userId() {
        return userId;
    }

    public FlowRunEnv userId(Long userId) {
        this.userId = userId;
        return this;
    }

    public String agentCode() {
        return agentCode;
    }

    public FlowRunEnv agentCode(String agentCode) {
        this.agentCode = agentCode;
        return this;
    }

    public String agentName() {
        return agentName;
    }

    public FlowRunEnv agentName(String agentName) {
        this.agentName = agentName;
        return this;
    }

    public String sessionId() {
        return sessionId;
    }

    public FlowRunEnv sessionId(String sessionId) {
        this.sessionId = sessionId;
        return this;
    }

    public String channel() {
        return channel;
    }

    public FlowRunEnv channel(String channel) {
        this.channel = channel;
        return this;
    }

    public Long defaultModelId() {
        return defaultModelId;
    }

    public FlowRunEnv defaultModelId(Long defaultModelId) {
        this.defaultModelId = defaultModelId;
        return this;
    }

    public boolean streaming() {
        return streaming;
    }

    public FlowRunEnv streaming(boolean streaming) {
        this.streaming = streaming;
        return this;
    }

    public Consumer<String> messageSink() {
        return messageSink;
    }

    public FlowRunEnv messageSink(Consumer<String> messageSink) {
        this.messageSink = messageSink == null ? delta -> {
        } : messageSink;
        return this;
    }

    public BiConsumer<String, String> eventSink() {
        return eventSink;
    }

    public FlowRunEnv eventSink(BiConsumer<String, String> eventSink) {
        this.eventSink = eventSink == null ? (event, json) -> {
        } : eventSink;
        return this;
    }

    public String nodeIdPrefix() {
        return nodeIdPrefix;
    }

    public FlowRunEnv nodeIdPrefix(String nodeIdPrefix) {
        this.nodeIdPrefix = nodeIdPrefix == null ? "" : nodeIdPrefix;
        return this;
    }

    public String prefixed(String nodeId) {
        return nodeIdPrefix + nodeId;
    }

    /** 请求取消（幂等）：引擎在节点间隙、迭代循环与 LLM 流式 delta 处轮询后退出 */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
