package vip.mseapp.ai.service;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话模型韧性装饰器：上游瞬态错误（5xx / 429 / 网络 IO，如网关 503 overloaded_error
 * "no healthy upstream"）自动退避重试 {@code extraAttempts} 次；重试耗尽仍失败且配置了
 * 备用模型时，整体切换备用模型再来一轮。由 {@link AiModelFactory} 织入，对话工作台 /
 * RPC 无状态通道 / 工作流 LLM 节点等全链路生效。
 *
 * <p>瞬态判定按官方客户端异常类型（openai-java / anthropic-java）：Retryable/Io 异常直接判定，
 * Service 异常按 statusCode() ≥ 500 或 429 判定；4xx 参数/鉴权类错误快速失败，不重试不切换。
 *
 * <p>流式语义：仅在「尚未向下游发出任何 token」时才允许重试或切换备用（已吐内容后出错无法
 * 安全重放，直接向上抛错交由会话层标记失败）。备用模型按行配置直接构建，不再嵌套备用
 * （failover 深度 1，天然防环）；非瞬态错误同样不触发切换。
 *
 * <p>选项契约（工具调用生死线）：必须覆写 {@link #getOptions()} 委托 primary——Spring AI 2.0.x
 * 的 ChatClient 以 {@code chatModel.getOptions().mutate()} 为基底合并请求选项，且仅当基底
 * builder 是 {@link ToolCallingChatOptions} 的 Builder 时才把 defaultToolCallbacks/toolContext
 * 合入（ToolCallingAdvisor 的工具执行循环同样以 prompt options instanceof ToolCallingChatOptions
 * 为门槛）；接口默认的泛型 options 会让工具被静默丢弃——模型收不到 tools 参数，退化为把
 * 自身工具调用模板当正文输出（「表演执行」）。
 */
public class ResilientChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ResilientChatModel.class);

    private final ChatModel primary;
    private final ChatModel backup;
    private final String primaryName;
    private final String backupName;
    /** 瞬态错误额外重试次数（0 = 不重试，直接看是否切换备用） */
    private final int extraAttempts;
    /** 重试退避毫秒（同一主/备模型两次尝试之间的固定间隔） */
    private final long backoffMillis;

    public ResilientChatModel(ChatModel primary, ChatModel backup, String primaryName, String backupName,
                              int extraAttempts, long backoffMillis) {
        this.primary = primary;
        this.backup = backup;
        this.primaryName = primaryName;
        this.backupName = backupName;
        this.extraAttempts = Math.max(0, extraAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return callWithRetry(primary, forTarget(prompt, primary), primaryName);
        } catch (RuntimeException e) {
            if (backup == null) {
                throw e;
            }
            // 2026-10-08 修复：有备用模型时主模型任意失败（含 401 鉴权类非瞬态错误——备用行持有
            // 独立密钥，恰是鉴权失效时最该切换的场景）都切换备用；重试仍仅对瞬态错误生效
            log.warn("[mse-ai] 主模型 {} 失败（transient={}），切换备用模型 {} 再试: {}",
                    primaryName, isTransient(e), backupName, brief(e));
            return callWithRetry(backup, forTarget(prompt, backup), backupName);
        }
    }

    private ChatResponse callWithRetry(ChatModel model, Prompt prompt, String name) {
        RuntimeException last = null;
        for (int attempt = 0; ; attempt++) {
            try {
                return model.call(prompt);
            } catch (RuntimeException e) {
                last = e;
                if (attempt >= extraAttempts || !isTransient(e)) {
                    break;
                }
                log.warn("[mse-ai] 模型 {} 第 {} 次调用失败（瞬态错误，{}ms 后重试）: {}",
                        name, attempt + 1, backoffMillis, brief(e));
                sleepBeforeRetry();
            }
        }
        throw last;
    }

    /**
     * 暴露 primary 的类型化选项（OpenAiChatOptions/AnthropicChatOptions 均实现
     * {@link ToolCallingChatOptions}）：ChatClient 以此为基底合入 defaultToolCallbacks——
     * 见类 javadoc 选项契约，不覆写即工具静默丢失。
     */
    @Override
    public ChatOptions getOptions() {
        return primary.getOptions();
    }

    /**
     * prompt options 按目标模型归一（primary 与备用各归各）：
     * <ul>
     * <li>ChatClient 正路（primary 且选项类型与 primary 默认类型一致）→ 原样放行：
     *     工具回调等字段已由 ChatClient 合入，重建反而丢字段；</li>
     * <li>其余场景 → 以目标模型默认 options 的 mutate 副本为底重建：①直调泛型 options——
     *     Spring AI 2.0.x 的 OpenAI/Anthropic 模型在 createRequest 对 prompt options
     *     <b>原生强转</b>为自家类型，泛型选项一进即 ClassCastException；②备用模型——
     *     prompt 选项此时携带的是 primary 行的 baseUrl/apiKey/model，原样放行同协议备用
     *     会被静默打回主端点、跨协议直接类型不符。重建拷贝运行期通用字段
     *     （temperature/maxTokens/topP）与工具字段（toolCallbacks/toolContext，切备用
     *     不丢工具）；model 仅 primary 透传——备用必须用自己行配置的 chat_model。</li>
     * </ul>
     */
    private Prompt forTarget(Prompt prompt, ChatModel target) {
        ChatOptions options = prompt.getOptions();
        if (options == null) {
            return prompt;
        }
        ChatOptions defaults = target.getDefaultOptions();
        boolean toPrimary = target == primary;
        if (defaults == null || (toPrimary && options.getClass() == defaults.getClass())) {
            return prompt;
        }
        ChatOptions.Builder<?> builder = defaults.mutate();
        if (options.getTemperature() != null) {
            builder.temperature(options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            builder.maxTokens(options.getMaxTokens());
        }
        if (options.getTopP() != null) {
            builder.topP(options.getTopP());
        }
        if (toPrimary && options.getModel() != null && !options.getModel().isBlank()) {
            builder.model(options.getModel());
        }
        // 工具字段换底：builder 与 defaults.mutate() 同源同对象，向其合入即向重建底合入
        if (options instanceof ToolCallingChatOptions fromToolOptions
                && builder instanceof ToolCallingChatOptions.Builder<?> targetBuilder) {
            List<ToolCallback> callbacks = fromToolOptions.getToolCallbacks();
            if (callbacks != null && !callbacks.isEmpty()) {
                targetBuilder.toolCallbacks(callbacks);
            }
            Map<String, Object> toolContext = fromToolOptions.getToolContext();
            if (toolContext != null && !toolContext.isEmpty()) {
                targetBuilder.toolContext(toolContext);
            }
        }
        return new Prompt(prompt.getInstructions(), builder.build());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            // emitted 跨重试与备用切换共享：一旦向下游发出内容即冻结，不再重放
            AtomicBoolean emitted = new AtomicBoolean(false);
            return streamWithRetry(primary, forTarget(prompt, primary), emitted, primaryName)
                    .onErrorResume(e -> {
                        if (backup == null || emitted.get() || !isTransient(e)) {
                            return Flux.error(e);
                        }
                        log.warn("[mse-ai] 主模型 {} 流式调用失败，切换备用模型 {} 再试: {}",
                                primaryName, backupName, brief(e));
                        return streamWithRetry(backup, forTarget(prompt, backup), emitted, backupName);
                    });
        });
    }

    private Flux<ChatResponse> streamWithRetry(ChatModel model, Prompt prompt, AtomicBoolean emitted, String name) {
        return retryStream(model, prompt, emitted, name, 0);
    }

    /** 递归重试：不经 reactor Retry 包装，耗尽后原样上抛真实错误（会话层能看到上游原始报文） */
    private Flux<ChatResponse> retryStream(ChatModel model, Prompt prompt, AtomicBoolean emitted,
                                           String name, int attempt) {
        return model.stream(prompt)
                .doOnNext(r -> emitted.set(true))
                .onErrorResume(e -> {
                    if (attempt >= extraAttempts || emitted.get() || !isTransient(e)) {
                        return Flux.error(e);
                    }
                    log.warn("[mse-ai] 模型 {} 流式调用失败（瞬态错误，{}ms 后重试第 {} 次）: {}",
                            name, backoffMillis, attempt + 1, brief(e));
                    return Mono.delay(Duration.ofMillis(backoffMillis))
                            .thenMany(Flux.defer(() -> retryStream(model, prompt, emitted, name, attempt + 1)));
                });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return primary.getDefaultOptions();
    }

    /**
     * 瞬态错误判定（沿 cause 链）：官方客户端 Retryable/Io 异常直接判定；
     * Service 异常按 HTTP 状态 ≥ 500 或 429 判定（覆盖 500/502/503/529 等）。
     */
    static boolean isTransient(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof OpenAIRetryableException || t instanceof OpenAIIoException
                    || t instanceof AnthropicRetryableException || t instanceof AnthropicIoException) {
                return true;
            }
            if (t instanceof OpenAIServiceException se && isTransientStatus(se.statusCode())) {
                return true;
            }
            if (t instanceof AnthropicServiceException se && isTransientStatus(se.statusCode())) {
                return true;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        return false;
    }

    private static boolean isTransientStatus(int statusCode) {
        return statusCode >= 500 || statusCode == 429;
    }

    private void sleepBeforeRetry() {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型重试等待被中断", ie);
        }
    }

    private static String brief(Throwable e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }
}
