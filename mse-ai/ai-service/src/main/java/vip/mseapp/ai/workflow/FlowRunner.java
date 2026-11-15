package vip.mseapp.ai.workflow;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mseapp.ai.service.AiPlatformProperties;
import vip.mseapp.ai.service.DbChatMemory;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.ai.workflow.model.FlowGraph;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.VarTypes;
import vip.mseapp.ai.workflow.model.WorkflowContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工作流运行入口：对话链路（chat/stream）与编辑器试运行共用的桥接层。
 *
 * <p>系统变量在启动前预置（sys.query/conversation/conversation_id/tenant_id/user_id/now）；
 * 有会话时运行成功后把「用户消息 + 最终回答」写入落库记忆（多轮连续）。
 * SSE 契约：message（增量）/ node（节点轨迹）/ done / error。
 *
 * <p>取消机制：运行中编排登记在本地注册表（key=会话号，试运行为随机 runId）。
 * 两条取消通道——① SSE 发送失败（客户端断开）联动取消，emitter 与运行同实例、必然生效；
 * ② 停止接口本地取消 + Redis 频道跨实例广播（多实例部署下停止请求可能落在非运行实例；
 * 无 Redis 时降级为仅本地，由 ① 兜底）。取消路径不写半截会话记忆。
 */
@Component
public class FlowRunner {

    private static final Logger log = LoggerFactory.getLogger(FlowRunner.class);

    /** 跨实例停止信号频道（载荷仅 sessionId，鉴权已在 HTTP 层完成） */
    private static final String STOP_CHANNEL = "mse:ai:flow-stop";

    /** 工作流执行线程（引擎与执行器均不依赖 ThreadLocal，可安全迁移线程；经 ContextExecutorService
     * 包装：提交时快照租户上下文、执行时自动恢复——记忆写入/运行历史/工具审计落库随世界路由） */
    private final ExecutorService executor = io.micrometer.context.ContextExecutorService.wrap(
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mse-ai-flow-run");
                thread.setDaemon(true);
                return thread;
            }));

    private final AgentWorkflowEngine engine;

    private final AiPlatformProperties platformProperties;

    private final DbChatMemory chatMemory;

    private final ObjectProvider<RedissonClient> redissonClient;

    private final WorkflowRunRecorder runRecorder;

    /** 运行中编排注册表（停止信号与断开联动用；任务 finally 移除防泄漏） */
    private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    /** 停止信号频道监听器 id（0 = 未订阅） */
    private int stopListenerId;

    public FlowRunner(AgentWorkflowEngine engine, AiPlatformProperties platformProperties,
                      DbChatMemory chatMemory, ObjectProvider<RedissonClient> redissonClient,
                      WorkflowRunRecorder runRecorder) {
        this.engine = engine;
        this.platformProperties = platformProperties;
        this.chatMemory = chatMemory;
        this.redissonClient = redissonClient;
        this.runRecorder = runRecorder;
    }

    /** 运行中编排句柄（env 取消标志 + 执行线程 Future） */
    private static final class ActiveRun {

        final FlowRunEnv env;

        volatile Future<?> future;

        ActiveRun(FlowRunEnv env) {
            this.env = env;
        }
    }

    /** 订阅跨实例停止信号（无 Redis 时不订阅，跨实例停止降级为断开联动兜底） */
    @PostConstruct
    public void subscribeStopChannel() {
        RedissonClient redisson = redissonClient.getIfAvailable();
        if (redisson == null) {
            return;
        }
        try {
            this.stopListenerId = redisson.getTopic(STOP_CHANNEL).addListener(String.class,
                    (channel, sessionId) -> cancelLocal(sessionId));
        } catch (Exception e) {
            log.warn("[mse-ai] 停止信号频道订阅失败（跨实例停止降级为断开联动兜底）: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (stopListenerId == 0) {
            return;
        }
        RedissonClient redisson = redissonClient.getIfAvailable();
        if (redisson != null) {
            try {
                redisson.getTopic(STOP_CHANNEL).removeListener(stopListenerId);
            } catch (Exception ignored) {
                // Redis 已不可用，忽略
            }
        }
    }

    /** 预置系统变量、全局参数（env.*）与开始节点入参 */
    public WorkflowContext prepareContext(FlowGraph graph, String message,
                                          Map<String, String> variables, FlowRunEnv env) {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setOutput("sys", "query", message);
        ctx.setOutput("sys", "conversation_id", env.sessionId());
        ctx.setOutput("sys", "tenant_id", env.tenantId());
        ctx.setOutput("sys", "user_id", env.userId());
        ctx.setOutput("sys", "now", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        ctx.setOutput("sys", "conversation", conversationHistory(env.sessionId()));
        // 全局参数：按声明类型转换默认值（集合/对象配 JSON 文本）并登记，赋值节点可在执行期按操作改写
        graph.globals().forEach(global -> {
            String name = String.valueOf(global.getOrDefault("name", "")).trim();
            if (!name.isBlank()) {
                String type = VarTypes.normalize(global.get("type"));
                Object value = VarTypes.coerce(global.get("value"), type, "全局参数「" + name + "」默认值");
                ctx.registerEnv(name, type, value);
                ctx.setOutput("env", name, value);
            }
        });
        if (graph.startId() != null) {
            FlowNode start = graph.node(graph.startId());
            start.listMap("variables").forEach(variable -> {
                String name = String.valueOf(variable.getOrDefault("name", "")).trim();
                if (!name.isBlank()) {
                    String value = variables == null ? null : variables.get(name);
                    // 按开始节点真实 id 预置（画布引用 {{startId.变量名}}）；"start" 别名兼容旧图与导入 DSL
                    ctx.setOutput(start.id(), name, value == null ? "" : value);
                    ctx.setOutput("start", name, value == null ? "" : value);
                }
            });
        }
        return ctx;
    }

    /** 同步运行：返回最终回答（answer 文本优先，否则 end 输出的展示串）；收口记运行历史 */
    public String runSync(FlowGraph graph, String message, Map<String, String> variables, FlowRunEnv env) {
        long start = System.currentTimeMillis();
        WorkflowContext ctx = null;
        try {
            ctx = prepareContext(graph, message, variables, env);
            engine.run(graph, ctx, env);
            persistMemory(env, ctx, message);
            runRecorder.record(env, ctx, message, vip.mseapp.ai.entity.AiWorkflowRun.STATUS_SUCCESS,
                    null, System.currentTimeMillis() - start);
            Object output = ctx.getFinalOutput();
            if (output instanceof Map || output instanceof List) {
                return AiJson.toJson(output);
            }
            String answer = ctx.getFinalAnswer();
            return answer != null && !answer.isBlank() ? answer : VarRenderer.display(output);
        } catch (Exception e) {
            runRecorder.record(env, ctx, message, env.isCancelled()
                            ? vip.mseapp.ai.entity.AiWorkflowRun.STATUS_CANCELLED
                            : vip.mseapp.ai.entity.AiWorkflowRun.STATUS_FAILED,
                    e instanceof vip.mseapp.core.exception.ServiceException se ? se.getMessage()
                            : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    /** 同步运行返回完整上下文（调试面板展示变量/轨迹）；收口记运行历史 */
    public WorkflowContext runContext(FlowGraph graph, String message, Map<String, String> variables,
                                      FlowRunEnv env) {
        long start = System.currentTimeMillis();
        WorkflowContext ctx = null;
        try {
            ctx = prepareContext(graph, message, variables, env);
            engine.run(graph, ctx, env);
            persistMemory(env, ctx, message);
            runRecorder.record(env, ctx, message, vip.mseapp.ai.entity.AiWorkflowRun.STATUS_SUCCESS,
                    null, System.currentTimeMillis() - start);
            return ctx;
        } catch (Exception e) {
            runRecorder.record(env, ctx, message, env.isCancelled()
                            ? vip.mseapp.ai.entity.AiWorkflowRun.STATUS_CANCELLED
                            : vip.mseapp.ai.entity.AiWorkflowRun.STATUS_FAILED,
                    e instanceof vip.mseapp.core.exception.ServiceException se ? se.getMessage()
                            : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    System.currentTimeMillis() - start);
            throw e;
        }
    }

    /** 流式运行（SSE）：message/node 事件实时推送，done 收口；取消路径跳过记忆持久化 */
    public SseEmitter stream(FlowGraph graph, String message, Map<String, String> variables,
                             FlowRunEnv env, Runnable onComplete) {
        SseEmitter emitter = new SseEmitter(platformProperties.getSseTimeoutMillis());
        FlowRunEnv runEnv = env.streaming(true);
        AtomicReference<Future<?>> futureRef = new AtomicReference<>();
        // 取消联动（幂等）：置取消标志 + interrupt 执行线程（阻塞型节点靠下一检查点退出）
        Runnable cancelRun = () -> {
            runEnv.cancel();
            Future<?> running = futureRef.get();
            if (running != null) {
                running.cancel(true);
            }
        };
        runEnv.messageSink(delta -> safeSend(emitter, "message",
                "{\"delta\":" + AiJson.toJson(delta) + "}", cancelRun));
        runEnv.eventSink((event, json) -> safeSend(emitter, event, json, cancelRun));

        // 登记运行：对话流按会话号；编辑器试运行无会话用随机 runId（取消靠断开联动）
        String runKey = env.sessionId() != null ? env.sessionId()
                : "debug-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        ActiveRun active = new ActiveRun(runEnv);
        activeRuns.put(runKey, active);

        Future<?> future = executor.submit(() -> {
            long start = System.currentTimeMillis();
            WorkflowContext ctx = null;
            try {
                ctx = prepareContext(graph, message, variables, runEnv);
                engine.run(graph, ctx, runEnv);
                persistMemory(runEnv, ctx, message);
                runRecorder.record(runEnv, ctx, message,
                        vip.mseapp.ai.entity.AiWorkflowRun.STATUS_SUCCESS, null,
                        System.currentTimeMillis() - start);
                safeSend(emitter, "done",
                        "{\"sessionId\":" + AiJson.toJson(env.sessionId()) + "}", cancelRun);
                completeQuietly(emitter);
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                String errorMsg = null;
                String status = vip.mseapp.ai.entity.AiWorkflowRun.STATUS_FAILED;
                if (e instanceof FlowCancelledException || runEnv.isCancelled()) {
                    // 半截回答不写记忆（persistMemory 在 engine.run 之后，取消路径到不了）；连接多已断开，推送静默失败无妨
                    status = vip.mseapp.ai.entity.AiWorkflowRun.STATUS_CANCELLED;
                    log.info("[mse-ai] 编排被取消: agent={}, session={}", env.agentCode(), env.sessionId());
                    safeSend(emitter, "done",
                            "{\"sessionId\":" + AiJson.toJson(env.sessionId()) + ",\"cancelled\":true}", cancelRun);
                    completeQuietly(emitter);
                } else {
                    errorMsg = e instanceof vip.mseapp.core.exception.ServiceException se ? se.getMessage()
                            : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    log.warn("[mse-ai] 编排流式运行失败: agent={}", env.agentCode(), e);
                    safeSend(emitter, "error", "{\"message\":" + AiJson.toJson(errorMsg) + "}", cancelRun);
                    completeQuietly(emitter);
                }
                runRecorder.record(runEnv, ctx, message, status, errorMsg,
                        System.currentTimeMillis() - start);
            } finally {
                activeRuns.remove(runKey);
            }
        });
        futureRef.set(future);
        active.future = future;
        emitter.onTimeout(() -> {
            cancelRun.run();
            completeQuietly(emitter);
        });
        emitter.onError(t -> {
            cancelRun.run();
            completeQuietly(emitter);
        });
        return emitter;
    }

    /** 会话历史渲染为 sys.conversation（DbChatMemory 自带窗口截断） */
    private String conversationHistory(String sessionId) {
        if (sessionId == null) {
            return "";
        }
        try {
            List<Message> messages = chatMemory.get(sessionId);
            StringBuilder sb = new StringBuilder();
            for (Message message : messages) {
                String role = message instanceof UserMessage ? "用户" : "助手";
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(role).append(": ").append(message.getText());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void persistMemory(FlowRunEnv env, WorkflowContext ctx, String message) {
        if (env.sessionId() == null) {
            return;
        }
        try {
            String answer = ctx.getFinalAnswer();
            if (answer == null || answer.isBlank()) {
                return;
            }
            chatMemory.add(env.sessionId(),
                    List.of(new UserMessage(message == null ? "" : message), new AssistantMessage(answer)));
        } catch (Exception e) {
            log.warn("[mse-ai] 编排会话记忆写入失败: session={}", env.sessionId(), e);
        }
    }

    private void safeSend(SseEmitter emitter, String event, String data) {
        safeSend(emitter, event, data, null);
    }

    private void safeSend(SseEmitter emitter, String event, String data, Runnable onFailure) {
        try {
            synchronized (emitter) {
                emitter.send(SseEmitter.event().name(event).data(data));
            }
        } catch (Exception e) {
            // 客户端断开等发送失败：终止 emitter 并联动取消运行（onError 回调再次取消，幂等）
            log.debug("[mse-ai] SSE 发送失败（客户端可能已断开），联动取消运行: {}", e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // emitter 已完成或已出错，忽略
            }
            if (onFailure != null) {
                onFailure.run();
            }
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 可能已因发送失败终止，忽略二次完成
        }
    }

    /** 取消运行（停止接口入口）：本地取消 + 跨实例广播；返回本机命中或信号已发出 */
    public boolean cancelRun(String sessionId) {
        boolean local = cancelLocal(sessionId);
        boolean published = publishStop(sessionId);
        return local || published;
    }

    /** 本地取消：移出注册表 + 置取消标志 + interrupt 执行线程（订阅回调与停止接口共用，幂等） */
    private boolean cancelLocal(String sessionId) {
        ActiveRun run = activeRuns.remove(sessionId);
        if (run == null) {
            return false;
        }
        run.env.cancel();
        Future<?> future = run.future;
        if (future != null) {
            future.cancel(true);
        }
        log.info("[mse-ai] 编排运行已请求取消: session={}", sessionId);
        return true;
    }

    /** 跨实例广播停止信号（多实例部署下停止请求可能落在非运行实例）；无 Redis/发布失败返回 false */
    private boolean publishStop(String sessionId) {
        RedissonClient redisson = redissonClient.getIfAvailable();
        if (redisson == null) {
            return false;
        }
        try {
            redisson.getTopic(STOP_CHANNEL).publish(sessionId);
            return true;
        } catch (Exception e) {
            log.warn("[mse-ai] 停止信号发布失败（Redis 不可用，跨实例停止降级为断开联动兜底）: {}", e.getMessage());
            return false;
        }
    }
}
