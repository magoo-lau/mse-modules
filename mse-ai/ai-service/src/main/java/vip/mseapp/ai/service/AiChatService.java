package vip.mseapp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import vip.mseapp.ai.entity.AiAgent;
import vip.mseapp.ai.entity.AiChatMessage;
import vip.mseapp.ai.entity.AiChatSession;
import vip.mseapp.ai.entity.AiModel;
import vip.mseapp.ai.kb.KbRetrievalService;
import vip.mseapp.ai.mapper.AiChatMessageMapper;
import vip.mseapp.ai.mapper.AiChatSessionMapper;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.TenantContextPropagation;
import vip.mseapp.core.exception.ServiceException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 对话编排：会话解析 → 智能体运行时组装 → 流式/同步调用 → 用量流水。
 *
 * <p>异步链路（SSE 订阅回调运行在 Reactor 线程）的租户上下文由框架自动传播
 * （starter-web 的 Reactor 自动传播 / 编排线程池装饰）；落库统一 {@link TenantContext#runAs}
 * 显式锚定世界（一切皆租户）：独立库租户落专属库、共享租户落绑定共享库、平台世界（0）落平台库。
 *
 * <p>SSE 事件：sources（RAG 引用来源，检索完成后先于正文推送）/
 * tool（工具执行轨迹：start/result 与模型轮次边界 turn/turn-end，完成后绑定了工具却
 * 零调用时推 unused 告警）/ message（增量文本）/ prompt（调试通道：初始提示词）/
 * usage（token 用量，多轮按轮求和）/ done / error。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiAgentService agentService;
    private final AiModelService modelService;
    private final AgentRuntime agentRuntime;
    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final UsageRecorder usageRecorder;
    private final AiPlatformProperties properties;
    private final vip.mseapp.ai.workflow.FlowRunner flowRunner;

    public AiChatService(AiAgentService agentService, AiModelService modelService,
                         AgentRuntime agentRuntime, AiChatSessionMapper sessionMapper,
                         AiChatMessageMapper messageMapper, UsageRecorder usageRecorder,
                         AiPlatformProperties properties,
                         vip.mseapp.ai.workflow.FlowRunner flowRunner) {
        this.agentService = agentService;
        this.modelService = modelService;
        this.agentRuntime = agentRuntime;
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.usageRecorder = usageRecorder;
        this.properties = properties;
        this.flowRunner = flowRunner;
    }

    /**
     * 开启对话链路的详细轨迹（模型轮次边界 + 每轮实际输入消息快照）：仅对话工作台
     * 持 {@code ai:chat:trace} 权限者启用——快照含记忆历史与知识库检索内容，
     * SSE turn 事件与 ext.turns 落库均随既有权限门控下发；RPC/开放通道保持轻量。
     */
    public void enableVerboseTrace(ResolvedChat ctx) {
        if (ctx.toolRecorder() != null) {
            ctx.toolRecorder().setVerbose(true);
        }
    }

    /** 解析完成的对话上下文（请求线程产出，贯穿异步链路）；graph 非空 = 画布编排智能体 */
    public record ResolvedChat(long tenantId, Long userId, String channel,
                               AiAgent agent, ChatClient client,
                               String sessionId, String model,
                               vip.mseapp.ai.workflow.model.FlowGraph graph,
                               ToolExecutionSupport.ToolExecutionRecorder toolRecorder) {

        /** 简单模式（无编排图）构造 */
        public ResolvedChat(long tenantId, Long userId, String channel, AiAgent agent, ChatClient client,
                            String sessionId, String model) {
            this(tenantId, userId, channel, agent, client, sessionId, model, null, null);
        }
    }

    /**
     * 准备对话：校验智能体可见性、解析/创建会话、组装客户端。
     *
     * @param conversationId 为 null 时创建新会话（标题取消息前 30 字）
     */
    public ResolvedChat prepare(String agentCode, String conversationId, String firstMessage,
                                Long tenantId, Long userId, String channel) {
        AiAgent agent = agentService.getPublishedVisible(agentCode, tenantId);
        checkModelAvailable(agent, tenantId);
        String sessionId = resolveSession(conversationId, firstMessage, agent, tenantId, userId);
        boolean flow = isFlow(agent);
        ToolExecutionSupport.ToolExecutionRecorder recorder =
                flow ? null : new ToolExecutionSupport.ToolExecutionRecorder();
        ChatClient client = flow ? null : agentRuntime.buildClient(agent,
                new AiToolService.ToolInvocationContext(agentCode, sessionId, tenantId),
                sessionId != null, recorder);
        String model = agent.getModelId() == null ? "default" : modelService.getById(agent.getModelId()).getChatModel();
        return new ResolvedChat(tenantId, userId, channel, agent, client, sessionId, model,
                flow ? vip.mseapp.ai.workflow.model.FlowGraph.parse(agent.getGraphJson()) : null, recorder);
    }

    /**
     * 准备无状态对话（RPC 契约未传 conversationId 时）：不建会话、不挂记忆，
     * 仅校验智能体与模型可用性；用量以伪会话号归集。
     */
    public ResolvedChat prepareStateless(String agentCode, Long tenantId, Long userId) {
        AiAgent agent = agentService.getPublishedVisible(agentCode, tenantId);
        checkModelAvailable(agent, tenantId);
        ChatClient client = isFlow(agent) ? null : agentRuntime.buildClient(agent,
                new AiToolService.ToolInvocationContext(agentCode, null, tenantId), false);
        String model = agent.getModelId() == null ? "default"
                : modelService.getById(agent.getModelId()).getChatModel();
        return new ResolvedChat(tenantId, userId, "rpc", agent, client, statelessSessionId(), model,
                isFlow(agent) ? vip.mseapp.ai.workflow.model.FlowGraph.parse(agent.getGraphJson()) : null, null);
    }

    /**
     * 准备开放 API 对话（/openapi/ai/**，APIKey 直调）：开放通道无登录用户，
     * 走无状态单轮（伪会话号、不建会话行）——此前复用 prepare 且 userId=null，
     * 建会话 INSERT 违反 user_id 非空约束（2026-10-03 用例 AI-KEY-002/003 实测）；
     * channel=openapi 保持用量通道区分。
     */
    public ResolvedChat prepareStatelessOpen(String agentCode, Long tenantId) {
        AiAgent agent = agentService.getPublishedVisible(agentCode, tenantId);
        checkModelAvailable(agent, tenantId);
        ChatClient client = isFlow(agent) ? null : agentRuntime.buildClient(agent,
                new AiToolService.ToolInvocationContext(agentCode, null, tenantId), false);
        String model = agent.getModelId() == null ? "default"
                : modelService.getById(agent.getModelId()).getChatModel();
        return new ResolvedChat(tenantId, null, "openapi", agent, client, statelessSessionId(), model,
                isFlow(agent) ? vip.mseapp.ai.workflow.model.FlowGraph.parse(agent.getGraphJson()) : null, null);
    }

    /**
     * 准备调试对话（智能体管理「调试」一次性试跑）：不建会话、不挂记忆（单轮无上下文），
     * 工具轨迹照常收集（SSE tool 事件 + ext 形态，由调试台实时展示逐步入参/返参）；
     * 并开启 verbose 详细轨迹（模型轮次边界 + 每轮实际输入消息快照，调试台全过程时间线用）；
     * 用量以 debug 通道伪会话号归集。智能体行由调用方给定（简单模式无草稿机制，当前已保存行）。
     */
    public ResolvedChat prepareDebug(AiAgent agent, Long tenantId, Long userId) {
        checkModelAvailable(agent, tenantId);
        ToolExecutionSupport.ToolExecutionRecorder recorder = isFlow(agent) ? null
                : new ToolExecutionSupport.ToolExecutionRecorder();
        if (recorder != null) {
            recorder.setVerbose(true);
        }
        ChatClient client = isFlow(agent) ? null : agentRuntime.buildClient(agent,
                new AiToolService.ToolInvocationContext(agent.getAgentCode(), null, tenantId), false, recorder);
        String model = agent.getModelId() == null ? "default"
                : modelService.getById(agent.getModelId()).getChatModel();
        return new ResolvedChat(tenantId, userId, "debug", agent, client, "debug-" + shortUuid(), model,
                isFlow(agent) ? vip.mseapp.ai.workflow.model.FlowGraph.parse(agent.getGraphJson()) : null, recorder);
    }

    /** 同步对话：返回完整回复（RPC / 开放 API / 网关非流式共用）；编排智能体走工作流引擎 */
    public String chat(ResolvedChat ctx, String message, Map<String, String> variables) {
        return chat(ctx, message, variables, null);
    }

    /**
     * 同步对话（可挂引用来源收集器）：consumer 非空时经 KbRagAdvisor 的 SOURCES_CONSUMER
     * 参数接收检索命中（RPC 无状态通道溯源用；工作流编排智能体不经 Advisor 检索，consumer 不生效）。
     */
    public String chat(ResolvedChat ctx, String message, Map<String, String> variables,
                       Consumer<List<KbRetrievalService.KbHit>> sourcesConsumer) {
        long start = System.currentTimeMillis();
        // 同步路径无 Reactor 上下文可依托：ThreadLocal 直塞（工具循环在调用线程内执行，
        // FriendlyToolCallingManager 借此补录超限轨迹）
        if (ctx.toolRecorder() != null) {
            vip.mseapp.ai.support.ToolTracePropagation.set(ctx.toolRecorder());
        }
        try {
            String content;
            if (ctx.graph() != null) {
                content = flowRunner.runSync(ctx.graph(), message, variables, flowEnv(ctx));
            } else {
                var spec = buildRequest(ctx, render(message, variables));
                if (sourcesConsumer != null) {
                    spec = spec.advisors(a -> a.param(
                            vip.mseapp.ai.kb.KbRagAdvisor.SOURCES_CONSUMER, sourcesConsumer));
                }
                content = spec.call().content();
            }
            usageRecorder.recordSimple(ctx.tenantId(), ctx.userId(), ctx.channel(),
                    ctx.agent().getAgentCode(), ctx.model(), ctx.sessionId(),
                    System.currentTimeMillis() - start, true, null);
            touchSession(ctx.tenantId(), ctx.sessionId());
            return content == null ? "" : content;
        } catch (Exception e) {
            usageRecorder.recordSimple(ctx.tenantId(), ctx.userId(), ctx.channel(),
                    ctx.agent().getAgentCode(), ctx.model(), ctx.sessionId(),
                    System.currentTimeMillis() - start, false, e.getMessage());
            throw e;
        } finally {
            vip.mseapp.ai.support.ToolTracePropagation.clear();
        }
    }

    /** 流式对话：增量经 SseEmitter 推送，完成/失败统一收口；编排智能体走工作流引擎（含 node 轨迹事件） */
    public SseEmitter stream(ResolvedChat ctx, String message, Map<String, String> variables) {
        return stream(ctx, message, variables, true);
    }

    /**
     * 流式对话（可关工具事件推送）。
     *
     * @param pushToolEvents 是否向 SSE 推送 tool 事件（入参/结果/耗时/轮次等逐步轨迹）——
     *                       对话工作台按 {@code ai:chat:trace} 权限判定传入；
     *                       调试通道（智能体管理「调试」）恒 true。关闭不影响 recorder
     *                       收集（数据集/轨迹仍随 ext 落库），仅不实时推送。
     */
    public SseEmitter stream(ResolvedChat ctx, String message, Map<String, String> variables,
                             boolean pushToolEvents) {
        if (ctx.graph() != null) {
            return flowRunner.stream(ctx.graph(), message, variables, flowEnv(ctx),
                    () -> touchSession(ctx.tenantId(), ctx.sessionId()));
        }
        SseEmitter emitter = new SseEmitter(properties.getSseTimeoutMillis());
        long start = System.currentTimeMillis();
        StringBuilder buffer = new StringBuilder();
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        String prompt = render(message, variables);

        // token 用量按轮归集（usage 块在每轮模型调用收尾到达，当前轮号由 recorder 的
        // phase=turn 边界事件驱动）：合计 = 各轮之和，修复原「最后一轮覆盖」少报
        List<Usage> roundUsages = new CopyOnWriteArrayList<>();
        AtomicInteger roundIndex = new AtomicInteger();

        // 工具/轮次事件消费（轮次计数恒需要；SSE 推送按调用方权限门控）+ 引用来源收集（完成回调写消息 ext）
        List<Map<String, Object>> collectedSources = new ArrayList<>();
        if (ctx.toolRecorder() != null) {
            ctx.toolRecorder().bind(event -> {
                if ("turn".equals(event.get("phase")) && event.get("index") instanceof Number index) {
                    roundIndex.set(index.intValue());
                }
                if (pushToolEvents) {
                    // 轮次边界附带「截至该轮的累计 token 用量」（该轮模型输出的 usage 元数据
                    // 先于工具执行到达，此处汇总即前 N 轮之和）——调试台差分得每轮用量
                    if ("turn".equals(event.get("phase")) || "turn-end".equals(event.get("phase"))) {
                        Usage cum = resolveTotalUsage(roundUsages, usageRef.get());
                        if (cum != null) {
                            Map<String, Object> cumUsage = new LinkedHashMap<>();
                            cumUsage.put("promptTokens", cum.getPromptTokens() == null ? 0 : cum.getPromptTokens());
                            cumUsage.put("completionTokens",
                                    cum.getCompletionTokens() == null ? 0 : cum.getCompletionTokens());
                            cumUsage.put("totalTokens", cum.getTotalTokens() == null ? 0 : cum.getTotalTokens());
                            event.put("cumUsage", cumUsage);
                        }
                    }
                    safeSend(emitter, "tool", vip.mseapp.ai.support.AiJson.toJson(event));
                }
            });
        }

        // 调试通道先发初始提示词（实际生效的系统提示词 + 变量渲染后的用户消息）；
        // 工具触发多轮时，每轮真实完整输入以 phase=turn 事件的 messages 快照跟进
        if ("debug".equals(ctx.channel())) {
            Map<String, Object> promptEvent = new LinkedHashMap<>();
            promptEvent.put("system", AgentRuntime.resolveSystemPrompt(ctx.agent()));
            promptEvent.put("user", prompt);
            safeSend(emitter, "prompt", vip.mseapp.ai.support.AiJson.toJson(promptEvent));
        }

        Flux<ChatClientResponse> flux = withSourceEmitter(buildRequest(ctx, prompt), emitter, collectedSources)
                .stream()
                .chatClientResponse()
                // 链内租户锚定：spring-ai 的记忆读/写（MessageChatMemoryAdvisor）运行在 subscribeOn
                // 调度器线程的链内操作符中，经 contextWrite + 框架 Reactor 自动传播恢复租户
                //（完成回调等 Runnable 形态不参与恢复，落库回调均已显式 runAs 锚定，双保险）；
                // recorder 随同一机制传播——模型内部工具循环线程上的轮次快照与超限拦截
                // （FriendlyToolCallingManager）得以回传轨迹事件
                .contextWrite(context -> {
                    var enriched = context.put(TenantContextPropagation.KEY, ctx.tenantId());
                    return ctx.toolRecorder() != null
                            ? enriched.put(vip.mseapp.ai.support.ToolTracePropagation.KEY, ctx.toolRecorder())
                            : enriched;
                });

        Runnable finish = () -> {
            usageRecorder.record(ctx.tenantId(), ctx.userId(), ctx.channel(),
                    ctx.agent().getAgentCode(), ctx.model(), ctx.sessionId(),
                    resolveTotalUsage(roundUsages, usageRef.get()), System.currentTimeMillis() - start, true, null);
            touchSession(ctx.tenantId(), ctx.sessionId());
            reportUnusedTools(ctx, emitter);
            persistAssistantExt(ctx, collectedSources);
        };

        Disposable disposable = flux.subscribe(
                response -> {
                    ChatResponse chatResponse = response.chatResponse();
                    if (chatResponse == null || chatResponse.getResult() == null) {
                        return;
                    }
                    if (chatResponse.getMetadata() instanceof ChatResponseMetadata metadata
                            && metadata.getUsage() != null
                            && metadata.getUsage().getTotalTokens() != null
                            && metadata.getUsage().getTotalTokens() > 0) {
                        usageRef.set(metadata.getUsage());
                        int slot = Math.max(roundIndex.get(), 0);
                        while (roundUsages.size() <= slot) {
                            roundUsages.add(null);
                        }
                        roundUsages.set(slot, metadata.getUsage());
                    }
                    if (chatResponse.getResult().getOutput() == null) {
                        return;
                    }
                    String delta = chatResponse.getResult().getOutput().getText();
                    if (delta != null && !delta.isEmpty()) {
                        buffer.append(delta);
                        safeSend(emitter, "message", "{\"delta\":" + vip.mseapp.ai.support.AiJson.toJson(delta) + "}");
                    }
                },
                error -> {
                    String msg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    log.warn("[mse-ai] 流式对话失败: agent={}, session={}", ctx.agent().getAgentCode(),
                            ctx.sessionId(), error);
                    usageRecorder.recordSimple(ctx.tenantId(), ctx.userId(), ctx.channel(),
                            ctx.agent().getAgentCode(), ctx.model(), ctx.sessionId(),
                            System.currentTimeMillis() - start, false, msg);
                    safeSend(emitter, "error", "{\"message\":" + vip.mseapp.ai.support.AiJson.toJson(msg) + "}");
                    completeQuietly(emitter);
                },
                () -> {
                    finish.run();
                    Usage usage = resolveTotalUsage(roundUsages, usageRef.get());
                    if (usage != null) {
                        safeSend(emitter, "usage", "{\"promptTokens\":" + usage.getPromptTokens()
                                + ",\"completionTokens\":" + usage.getCompletionTokens()
                                + ",\"totalTokens\":" + usage.getTotalTokens() + "}");
                    }
                    Map<String, Object> donePayload = new LinkedHashMap<>();
                    donePayload.put("sessionId", ctx.sessionId());
                    donePayload.put("elapsedMs", System.currentTimeMillis() - start);
                    if (ctx.toolRecorder() != null) {
                        // 总轮次 = 边界数 + 1（最后一轮不再请求工具、无边界）
                        donePayload.put("turns", ctx.toolRecorder().turnCount() + 1);
                        donePayload.put("toolCalls", ctx.toolRecorder().executionCount());
                    }
                    safeSend(emitter, "done", vip.mseapp.ai.support.AiJson.toJson(donePayload));
                    completeQuietly(emitter);
                });

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            log.warn("[mse-ai] 流式对话超时: session={}", ctx.sessionId());
        });
        emitter.onError(t -> disposable.dispose());
        return emitter;
    }

    /**
     * 合计本次运行的 token 用量：优先按轮求和（每轮取该轮最后一个 usage 块，修复
     * 原「最后一轮覆盖」少报）；末槽恰好等于前几槽之和时判定为聚合响应落在末槽
     * （部分链路会在收尾补发全量聚合 usage），直接取末槽避免双计；无按轮数据回退原口径。
     */
    private static Usage resolveTotalUsage(List<Usage> roundUsages, Usage fallback) {
        if (roundUsages.isEmpty()) {
            return fallback;
        }
        int prompt = 0;
        int completion = 0;
        long sumButLast = 0;
        Usage last = null;
        boolean any = false;
        for (int i = 0; i < roundUsages.size(); i++) {
            Usage usage = roundUsages.get(i);
            if (usage == null || usage.getTotalTokens() == null) {
                continue;
            }
            any = true;
            long total = usage.getTotalTokens();
            if (i < roundUsages.size() - 1) {
                sumButLast += total;
            }
            prompt += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            completion += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            last = usage;
        }
        if (!any) {
            return fallback;
        }
        if (last != null && last.getTotalTokens() != null && sumButLast > 0
                && last.getTotalTokens() == sumButLast) {
            return last;
        }
        final int promptTokens = prompt;
        final int completionTokens = completion;
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return promptTokens;
            }

            @Override
            public Integer getCompletionTokens() {
                return completionTokens;
            }

            @Override
            public Integer getTotalTokens() {
                return promptTokens + completionTokens;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }

    /** 模型租户可用性校验（agent.modelId 为空表示用平台默认模型，跳过；智能体绑定对话模型） */
    private void checkModelAvailable(AiAgent agent, Long tenantId) {
        if (agent.getModelId() != null) {
            modelService.listAvailableForTenant(tenantId, AiModel.MODEL_TYPE_CHAT).stream()
                    .filter(m -> m.getId().equals(agent.getModelId()))
                    .findFirst()
                    .orElseThrow(() -> new ServiceException(403, "当前租户未开放该模型"));
        }
    }

    /** 是否画布编排智能体 */
    private static boolean isFlow(AiAgent agent) {
        return "flow".equals(agent.getOrchestrationType());
    }

    /** 编排运行环境（显式携带，引擎可在任意线程执行） */
    private FlowRunEnv flowEnv(ResolvedChat ctx) {
        return new FlowRunEnv()
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .channel(ctx.channel())
                .agentCode(ctx.agent().getAgentCode())
                .agentName(ctx.agent().getName())
                .sessionId(ctx.sessionId())
                .defaultModelId(ctx.agent().getModelId());
    }

    private ChatClient.ChatClientRequestSpec buildRequest(ResolvedChat ctx, String prompt) {
        ChatClient.ChatClientRequestSpec spec = ctx.client().prompt().user(prompt);
        if (ctx.sessionId() != null) {
            spec = spec.advisors(a -> a.param(
                    org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID, ctx.sessionId()));
        }
        if (ctx.agent().getTemperature() != null) {
            spec = spec.options(ChatOptions.builder().temperature(ctx.agent().getTemperature()));
        }
        return spec;
    }

    /**
     * 流式对话的 sources 事件挂载：检索完成即推送引用来源（先于正文，
     * 前端渲染消息引用面板）。仅在流式 HTTP 通道启用，RPC 通道无回调载体。
     * collector 非空时同步收集一份（完成回调写消息 ext，刷新后回放恢复）。
     */
    @SuppressWarnings("unchecked")
    public ChatClient.ChatClientRequestSpec withSourceEmitter(ChatClient.ChatClientRequestSpec spec,
                                                              SseEmitter emitter) {
        return withSourceEmitter(spec, emitter, null);
    }

    @SuppressWarnings("unchecked")
    public ChatClient.ChatClientRequestSpec withSourceEmitter(ChatClient.ChatClientRequestSpec spec,
                                                              SseEmitter emitter,
                                                              List<Map<String, Object>> collector) {
        return spec.advisors(a -> a.param(vip.mseapp.ai.kb.KbRagAdvisor.SOURCES_CONSUMER,
                (Consumer<List<KbRetrievalService.KbHit>>) hits -> {
                    List<Map<String, Object>> sources = new ArrayList<>();
                    for (int i = 0; i < hits.size(); i++) {
                        KbRetrievalService.KbHit hit = hits.get(i);
                        String content = hit.content();
                        sources.add(Map.of(
                                "index", i + 1,
                                "chunkId", hit.chunkId() == null ? "" : hit.chunkId(),
                                "fileName", hit.fileName() == null ? "" : hit.fileName(),
                                "score", hit.score(),
                                "excerpt", content == null ? ""
                                        : content.substring(0, Math.min(200, content.length()))));
                    }
                    if (collector != null) {
                        collector.addAll(sources);
                    }
                    safeSend(emitter, "sources",
                            "{\"sources\":" + vip.mseapp.ai.support.AiJson.toJson(sources) + "}");
                }));
    }

    /**
     * 完成时「绑定了工具却零工具执行」判定与告警：resolved=false 表示绑定的工具
     * 一个都没解析成功（登记缺失/部署形态不符）；resolved=true 表示工具可用但模型
     * 一次都没调用（端点不支持 function calling 时模型只会照提示词输出伪执行文本）。
     */
    private void reportUnusedTools(ResolvedChat ctx, SseEmitter emitter) {
        ToolExecutionSupport.ToolExecutionRecorder recorder = ctx.toolRecorder();
        List<String> bound = splitCsv(ctx.agent().getToolCodes());
        if (recorder == null || bound.isEmpty() || recorder.hasExecutions()) {
            return;
        }
        boolean resolved = recorder.resolvedCount() > 0;
        if (resolved) {
            log.warn("[mse-ai] 智能体 {} 绑定了工具 {} 但模型一次都未调用（确认模型端点是否支持 function calling）: session={}",
                    ctx.agent().getAgentCode(), bound, ctx.sessionId());
        } else {
            log.warn("[mse-ai] 智能体 {} 绑定的工具 {} 均未解析成功（检查工具登记与部署形态）: session={}",
                    ctx.agent().getAgentCode(), bound, ctx.sessionId());
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", "unused");
        event.put("resolved", resolved);
        event.put("boundTools", bound);
        safeSend(emitter, "tool", vip.mseapp.ai.support.AiJson.toJson(event));
    }

    /**
     * 完成时把结构化扩展信息（工具数据集 + 引用来源 + 工具调用轨迹 + 轮次输入快照）写入
     * 本会话最新一条 assistant 消息的 ext 列（前端历史回放恢复数据卡片、引用面板、调用轨迹
     * 与每轮模型输入）；无结构化信息时不落库。伪会话号（rpc-/debug- 前缀，无状态与调试通道）
     * 无消息可写，跳过。turns 仅 verbose 模式（工作台持 ai:chat:trace 权限者）收集，
     * 下发侧按同一权限剔除。
     */
    private void persistAssistantExt(ResolvedChat ctx, List<Map<String, Object>> sources) {
        ToolExecutionSupport.ToolExecutionRecorder recorder = ctx.toolRecorder();
        if (recorder == null || ctx.sessionId() == null || ctx.sessionId().isBlank()
                || !ctx.sessionId().chars().allMatch(Character::isDigit)) {
            return;
        }
        List<ToolExecutionSupport.DatasetPayload> datasets = recorder.datasets();
        List<ToolExecutionSupport.Step> toolCalls = recorder.steps();
        List<Map<String, Object>> turns = recorder.isVerbose() ? recorder.turnSnapshots() : List.of();
        if (datasets.isEmpty() && toolCalls.isEmpty() && turns.isEmpty() && (sources == null || sources.isEmpty())) {
            return;
        }
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("datasets", datasets);
        if (!toolCalls.isEmpty()) {
            ext.put("toolCalls", toolCalls);
        }
        if (!turns.isEmpty()) {
            ext.put("turns", turns);
        }
        if (sources != null && !sources.isEmpty()) {
            ext.put("sources", sources);
        }
        try {
            TenantContext.runAs(ctx.tenantId(), () -> {
                AiChatMessage latest = messageMapper.selectOne(new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getSessionId, ctx.sessionId())
                        .eq(AiChatMessage::getRole, "assistant")
                        .orderByDesc(AiChatMessage::getId)
                        .last("LIMIT 1"));
                if (latest != null) {
                    latest.setExt(vip.mseapp.ai.support.AiJson.toJson(ext));
                    messageMapper.updateById(latest);
                }
            });
        } catch (Exception e) {
            log.warn("[mse-ai] 消息扩展信息（数据结果/引用）落库失败: session={}", ctx.sessionId(), e);
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** {{var}} 变量渲染（空变量表原样返回） */
    public String render(String template, Map<String, String> variables) {
        if (variables == null || variables.isEmpty() || template == null) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }

    private String resolveSession(String conversationId, String firstMessage, AiAgent agent,
                                  Long tenantId, Long userId) {
        if (conversationId != null && !conversationId.isBlank()) {
            AiChatSession session = TenantContext.runAs(tenantId, () ->
                    sessionMapper.selectById(Long.valueOf(conversationId)));
            if (session == null || !tenantId.equals(session.getTenantId())) {
                throw new ServiceException(404, "会话不存在");
            }
            return conversationId;
        }
        AiChatSession session = new AiChatSession();
        session.setTenantId(tenantId);
        session.setTitle(firstMessage == null ? "新对话"
                : firstMessage.substring(0, Math.min(30, firstMessage.length())));
        session.setAgentCode(agent.getAgentCode());
        session.setUserId(userId);
        session.setLastActiveTime(LocalDateTime.now());
        TenantContext.runAs(tenantId, () -> sessionMapper.insert(session));
        return String.valueOf(session.getId());
    }

    private void touchSession(Long tenantId, String sessionId) {
        // 无状态通道的 sessionId 是 "rpc-…" 临时串（非 ai_chat_session 主键），无会话可触达
        if (sessionId == null || !sessionId.chars().allMatch(Character::isDigit)) {
            return;
        }
        TenantContext.runAs(tenantId, () -> {
            AiChatSession session = sessionMapper.selectById(Long.valueOf(sessionId));
            if (session != null) {
                session.setLastActiveTime(LocalDateTime.now());
                sessionMapper.updateById(session);
            }
        });
    }

    private void safeSend(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            // 客户端断开等发送失败：主动终止 emitter（触发 onError/onCompletion → dispose 流订阅），
            // 避免模型/工具循环在无消费者的情况下继续空跑
            log.debug("[mse-ai] SSE 发送失败（客户端可能已断开），主动终止推送: {}", e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // emitter 已完成或已出错，忽略
            }
        }
    }

    /** 完成收口（可能已因发送失败终止，二次 complete 容器会抛错，吞掉即可） */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[mse-ai] SSE 完成回调异常（可能已终止）: {}", e.getMessage());
        }
    }

    /**
     * 会话分页（HTTP 线程）。会话数据随世界路由（与创建链同世界）：独立库租户落专属库、
     * 共享租户落绑定共享库 + 行级注入；显式租户/用户归属条件保留作防御。
     */
    public Page<AiChatSession> pageSessions(long pageNum, long pageSize, Long userId, String agentCode) {
        Long world = TenantContext.getRequiredTenantId();
        LambdaQueryWrapper<AiChatSession> wrapper = new LambdaQueryWrapper<AiChatSession>()
                .eq(AiChatSession::getTenantId, world)
                .eq(AiChatSession::getUserId, userId)
                .orderByDesc(AiChatSession::getLastActiveTime);
        if (agentCode != null && !agentCode.isBlank()) {
            wrapper.eq(AiChatSession::getAgentCode, agentCode);
        }
        return TenantContext.runAs(world, () -> sessionMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
    }

    /** 改名（随会话行世界路由 + 归属校验） */
    public void renameSession(Long sessionId, Long userId, String title) {
        AiChatSession session = TenantContext.runAs(TenantContext.getRequiredTenantId(),
                () -> sessionMapper.selectById(sessionId));
        if (session == null || !userId.equals(session.getUserId())) {
            throw new ServiceException(404, "会话不存在");
        }
        session.setTitle(title);
        TenantContext.runAs(session.getTenantId(), () -> sessionMapper.updateById(session));
    }

    /** 删除会话及其消息（随会话行世界路由 + 归属校验） */
    public void deleteSession(Long sessionId, Long userId, DbChatMemory chatMemory) {
        AiChatSession session = TenantContext.runAs(TenantContext.getRequiredTenantId(),
                () -> sessionMapper.selectById(sessionId));
        if (session == null || !userId.equals(session.getUserId())) {
            throw new ServiceException(404, "会话不存在");
        }
        TenantContext.runAs(session.getTenantId(), () -> chatMemory.clear(String.valueOf(sessionId)));
        TenantContext.runAs(session.getTenantId(), () -> sessionMapper.deleteById(sessionId));
    }

    /**
     * 停止运行中的编排对话：会话归属校验后经 FlowRunner 本地取消 + 跨实例广播取消信号。
     * 会话不存在/非本人返回 404（与 rename/delete 同口径）；未在运行编排返回 false（幂等）。
     */
    public boolean stopFlowRun(String sessionId, Long userId) {
        AiChatSession session = TenantContext.runAs(TenantContext.getRequiredTenantId(),
                () -> sessionMapper.selectById(Long.valueOf(sessionId)));
        if (session == null || !userId.equals(session.getUserId())) {
            throw new ServiceException(404, "会话不存在");
        }
        return flowRunner.cancelRun(sessionId);
    }

    /** 生成无状态调用的伪会话标识（仅用于用量归集，不落库） */
    public String statelessSessionId() {
        return "rpc-" + shortUuid();
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
