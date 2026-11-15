package vip.mseapp.ai.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.exec.NodeExecutor;
import vip.mseapp.ai.workflow.model.FlowEdge;
import vip.mseapp.ai.workflow.model.FlowGraph;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.NodeTrace;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 工作流引擎：从开始节点沿激活边驱动执行器拓扑运行。
 *
 * <p>激活语义——节点在其任一"有效入边"的源节点完成后变为可执行（OR-join，先到先跑，至多执行一次）；
 * 分支路由节点（condition/classifier）把命中的出口写 {@code ctx.branch}，仅匹配 sourceHandle
 * 的出边激活下游。同一轮次可执行的多个节点（多活跃路径）经节点池并行执行
 * （{@code mse.ai.flow.parallel} 可回退串行）；节点失败默认快速失败——并行批次内取消同级任务后冒泡，
 * 节点级 {@code errorStrategy=continue} 时记轨迹后继续（下游按空值渲染）。
 * 执行全程可在任意线程进行（ctx/env 显式传参、上下文并发安全）。
 */
@Component
public class AgentWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowEngine.class);

    private final Map<String, NodeExecutor> executors = new HashMap<>();

    private final FlowProperties properties;

    /** 并行节点执行池（daemon cached 池避免迭代子图嵌套提交饿死；经 ContextExecutorService 包装
     * 透传租户上下文——节点内工具审计等落库随世界路由） */
    private final ExecutorService nodePool = io.micrometer.context.ContextExecutorService.wrap(
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mse-ai-flow-node");
                thread.setDaemon(true);
                return thread;
            }));

    public AgentWorkflowEngine(List<NodeExecutor> executors, FlowProperties properties) {
        executors.forEach(executor -> this.executors.put(executor.type(), executor));
        this.properties = properties;
    }

    /** 执行整图（前置校验由保存/试运行入口完成；运行期仅防御开始节点缺失） */
    public void run(FlowGraph graph, WorkflowContext ctx, FlowRunEnv env) {
        String startId = graph.startId();
        if (startId == null) {
            throw new ServiceException(400, "编排图缺少开始节点");
        }
        runInternal(graph, ctx, env, System.currentTimeMillis() + properties.getTimeoutMillis());
    }

    private void runInternal(FlowGraph graph, WorkflowContext ctx, FlowRunEnv env, long deadline) {
        markPassthrough(graph);
        // scheduled = 已派发（执行中或已完成），executed = 已完成（下游激活判定口径）
        Set<String> scheduled = ConcurrentHashMap.newKeySet();
        Set<String> executed = ConcurrentHashMap.newKeySet();
        while (true) {
            // 取消检查点（与超时并列；runSubgraph 复用本循环，迭代子图内部同样覆盖）
            if (env.isCancelled()) {
                throw new FlowCancelledException();
            }
            if (System.currentTimeMillis() > deadline) {
                throw new ServiceException(504, "工作流执行超时（" + properties.getTimeoutMillis() + "ms）");
            }
            List<FlowNode> ready = findExecutables(graph, ctx, scheduled, executed);
            if (ready.isEmpty()) {
                return;
            }
            if (ready.size() == 1 || !properties.isParallel()) {
                FlowNode node = ready.get(0);
                scheduled.add(node.id());
                executeNode(node, ctx, env);
                executed.add(node.id());
                continue;
            }
            runBatch(ready, ctx, env, deadline, scheduled, executed);
        }
    }

    /**
     * 并行执行一批互不依赖的可执行节点：完成一个激活一个（failfast 失败立即取消同级并冒泡，
     * 取消同样立即冒泡），全部完成后整批记入 executed 释放下游。
     */
    private void runBatch(List<FlowNode> batch, WorkflowContext ctx, FlowRunEnv env, long deadline,
                          Set<String> scheduled, Set<String> executed) {
        ExecutorCompletionService<Object> completion = new ExecutorCompletionService<>(nodePool);
        List<Future<?>> futures = new ArrayList<>(batch.size());
        for (FlowNode node : batch) {
            scheduled.add(node.id());
            futures.add(completion.submit(() -> {
                executeNode(node, ctx, env);
                return null;
            }));
        }
        ServiceException failure = null;
        boolean cancelled = false;
        for (int done = 0; done < futures.size(); done++) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                futures.forEach(future -> future.cancel(true));
                throw new ServiceException(504, "工作流执行超时（" + properties.getTimeoutMillis() + "ms）");
            }
            Future<?> future;
            try {
                future = completion.poll(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new FlowCancelledException();
            }
            if (future == null) {
                futures.forEach(f -> f.cancel(true));
                throw new ServiceException(504, "工作流执行超时（" + properties.getTimeoutMillis() + "ms）");
            }
            try {
                future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof FlowCancelledException || env.isCancelled()) {
                    futures.forEach(f -> f.cancel(true));
                    throw cause instanceof FlowCancelledException fce ? fce : new FlowCancelledException();
                }
                if (failure == null) {
                    failure = cause instanceof ServiceException se ? se
                            : new ServiceException(500, cause.getMessage() == null
                            ? cause.getClass().getSimpleName() : cause.getMessage());
                }
                // failfast：取消同级未完成任务（executeNode 内部按取消语义收尾），排空后冒泡首个失败
                futures.forEach(f -> f.cancel(true));
                cancelled = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new FlowCancelledException();
            }
        }
        if (cancelled && failure != null) {
            // 排空已中断任务的 Future（短暂阻塞等待 interrupt 收尾，避免轨迹/事件竞态）
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception ignored) {
                    // CancellationException / 已归档的失败，忽略
                }
            }
            throw failure;
        }
        batch.forEach(node -> executed.add(node.id()));
    }

    /**
     * 流式直通预判：仅当某回复节点以"纯引用"消费某 LLM 节点输出（{{llm1.text}} 整串）
     * 时该 LLM 才允许流式增量下发，Answer 渲染时跳过已流式内容——避免增量+全文重复。
     */
    private void markPassthrough(FlowGraph graph) {
        Set<String> passthrough = new HashSet<>();
        for (FlowNode node : graph.nodes()) {
            if (!"answer".equals(node.type())) {
                continue;
            }
            String content = node.str("content", "").trim();
            java.util.List<String> refs = vip.mseapp.ai.workflow.model.VarRenderer.refs(content);
            if (vip.mseapp.ai.workflow.model.VarRenderer.isPureRef(content) && refs.size() == 1) {
                String owner = refs.get(0).split("\\.", 2)[0];
                FlowNode source = graph.node(owner);
                if (source != null && "llm".equals(source.type())) {
                    passthrough.add(owner);
                }
            }
        }
        for (FlowNode node : graph.nodes()) {
            if ("llm".equals(node.type())) {
                node.data().put("passthrough", passthrough.contains(node.id()));
            }
        }
    }

    /** 迭代节点子图入口：子图复用同一驱动循环，轨迹/事件 id 加前缀隔离（独立超时预算） */
    public void runSubgraph(FlowGraph graph, WorkflowContext ctx, FlowRunEnv env, String prefix) {
        String startId = graph.startId();
        if (startId == null) {
            throw new ServiceException(400, "迭代子图缺少开始节点");
        }
        runInternal(graph, ctx, env.nodeIdPrefix(prefix),
                System.currentTimeMillis() + properties.getTimeoutMillis());
    }

    /**
     * 当前全部可执行节点（并行批次）：任一有效入边的源节点已完成即激活（OR-join，至多执行一次）。
     * 原单节点版 findExecutable 的「声明顺序取第一个」语义由调用方在串行回退时保留。
     */
    private List<FlowNode> findExecutables(FlowGraph graph, WorkflowContext ctx,
                                           Set<String> scheduled, Set<String> executed) {
        List<FlowNode> ready = new ArrayList<>();
        for (FlowNode node : graph.nodes()) {
            if (scheduled.contains(node.id())) {
                continue;
            }
            List<FlowEdge> inEdges = graph.inEdges(node.id());
            if (inEdges.isEmpty()) {
                if (node.id().equals(graph.startId())) {
                    ready.add(node);
                }
                continue;
            }
            for (FlowEdge edge : inEdges) {
                if (!executed.contains(edge.source())) {
                    continue;
                }
                String branch = ctx.getBranch(edge.source());
                if (branch == null || edge.sourceHandle() == null || branch.equals(edge.sourceHandle())) {
                    ready.add(node);
                    break;
                }
            }
        }
        return ready;
    }

    private void executeNode(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        NodeExecutor executor = executors.get(node.type());
        String prefixedId = env.prefixed(node.id());
        if (executor == null) {
            throw new ServiceException(400, "未知节点类型: " + node.type());
        }
        NodeTrace trace = new NodeTrace(prefixedId, node.type(), node.title());
        ctx.traces().add(trace);
        emitNode(env, trace, null);
        long start = System.currentTimeMillis();
        try {
            executor.execute(node, ctx, env);
            trace.setStatus(NodeTrace.SUCCESS);
            trace.setOutput(summarize(node, ctx));
        } catch (Exception e) {
            if (e instanceof FlowCancelledException || env.isCancelled()) {
                // 取消（含 interrupt 打断阻塞节点导致的普通异常）：轨迹记 cancelled 并按取消冒泡，不走 errorStrategy
                trace.setStatus(NodeTrace.CANCELLED);
                trace.setError("已取消");
                emitNode(env, trace, System.currentTimeMillis() - start);
                throw e instanceof FlowCancelledException fce ? fce : new FlowCancelledException();
            }
            String msg = e instanceof ServiceException se ? se.getMessage()
                    : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("[mse-ai] 编排节点执行失败: agent={}, node={} - {}", env.agentCode(), prefixedId, msg);
            trace.setStatus(NodeTrace.FAILED);
            trace.setError(msg);
            boolean continueOnError = "continue".equalsIgnoreCase(node.str("errorStrategy", "failfast"));
            emitNode(env, trace, System.currentTimeMillis() - start);
            if (!continueOnError) {
                throw e instanceof ServiceException se ? se
                        : new ServiceException(500, "节点「" + node.title() + "」执行失败: " + msg);
            }
            return;
        }
        trace.setElapsedMs(System.currentTimeMillis() - start);
        emitNode(env, trace, trace.getElapsedMs());
    }

    private void emitNode(FlowRunEnv env, NodeTrace trace, Long elapsedMs) {
        if (!env.streaming()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", trace.getNodeId());
            payload.put("nodeType", trace.getNodeType());
            payload.put("title", trace.getTitle());
            payload.put("status", trace.getStatus());
            payload.put("elapsedMs", elapsedMs);
            payload.put("output", trace.getOutput());
            payload.put("error", trace.getError());
            env.eventSink().accept("node", AiJson.toJson(payload));
        } catch (Exception e) {
            log.debug("[mse-ai] node 事件发送失败: {}", e.getMessage());
        }
    }

    /** 节点主输出摘要（前端轨迹展示用，截断 300 字符） */
    private String summarize(FlowNode node, WorkflowContext ctx) {
        String ref = switch (node.type()) {
            case "llm" -> node.id() + ".text";
            case "knowledge" -> node.id() + ".result";
            case "tool" -> node.id() + ".text";
            case "dataset" -> node.id() + ".markdown";
            case "classifier" -> node.id() + ".class_name";
            case "extractor", "code" -> null;
            case "assign" -> node.id() + ".assigned";
            case "template", "aggregator" -> node.id() + ".output";
            case "iteration" -> node.id() + ".output";
            case "answer" -> node.id() + ".text";
            case "end" -> node.id() + ".output";
            default -> null;
        };
        String text = ref == null ? null : ctx.str(ref);
        if (text == null || text.isBlank()) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            ctx.snapshot().forEach((key, value) -> {
                if (key.startsWith(node.id() + ".")) {
                    outputs.put(key.substring(node.id().length() + 1), value);
                }
            });
            text = outputs.isEmpty() ? null : AiJson.toJson(outputs);
        }
        if (text != null && text.length() > 300) {
            text = text.substring(0, 300) + "…";
        }
        return text;
    }
}
