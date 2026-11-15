package vip.mseapp.ai.workflow.exec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import vip.mseapp.ai.service.AiModelFactory;
import vip.mseapp.ai.service.AiModelService;
import vip.mseapp.ai.service.UsageRecorder;
import vip.mseapp.ai.workflow.FlowCancelledException;
import vip.mseapp.ai.workflow.FlowRunEnv;
import vip.mseapp.ai.workflow.model.FlowNode;
import vip.mseapp.ai.workflow.model.VarRenderer;
import vip.mseapp.ai.workflow.model.WorkflowContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.Iterator;

/**
 * LLM 节点：提示词模板 + 节点级模型/温度覆盖 + 会话记忆注入。
 *
 * <p>流式直通：仅当答案节点以"纯引用"消费本节点输出（引擎预判 passthrough）
 * 时才向客户端推增量，避免 Answer 渲染时重复下发。每节点独立记用量流水。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(LlmNodeExecutor.class);

    private final AiModelFactory modelFactory;

    private final AiModelService modelService;

    private final UsageRecorder usageRecorder;

    public LlmNodeExecutor(AiModelFactory modelFactory, AiModelService modelService, UsageRecorder usageRecorder) {
        this.modelFactory = modelFactory;
        this.modelService = modelService;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public void execute(FlowNode node, WorkflowContext ctx, FlowRunEnv env) {
        String prompt = VarRenderer.render(node.str("prompt", "{{sys.query}}"), ctx.resolver());
        if (prompt == null || prompt.isBlank()) {
            throw new ServiceException(400, "节点「" + node.title() + "」提示词为空");
        }
        Long modelId = node.lng("modelId", env.defaultModelId());
        ChatModel chatModel = modelFactory.getChatModel(modelId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        String systemPrompt = VarRenderer.render(node.str("systemPrompt", null), ctx.resolver());
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.defaultSystem(systemPrompt);
        }
        ChatClient.ChatClientRequestSpec spec = builder.build().prompt().user(prompt);
        Double temperature = node.dbl("temperature", null);
        if (temperature != null) {
            spec = spec.options(ChatOptions.builder().temperature(temperature));
        }

        boolean passthrough = env.streaming() && node.bool("passthrough", false);
        long start = System.currentTimeMillis();
        StringBuilder text = new StringBuilder();
        Usage[] usageRef = new Usage[1];

        if (passthrough) {
            Flux<ChatClientResponse> flux = spec.stream().chatClientResponse();
            // 显式 iterator：取消时可 dispose（BlockingIterable 迭代器实现 Disposable）中断上游 HTTP
            Iterator<ChatClientResponse> iterator = flux.toIterable().iterator();
            while (iterator.hasNext()) {
                if (env.isCancelled()) {
                    if (iterator instanceof Disposable disposable) {
                        disposable.dispose();
                    }
                    throw new FlowCancelledException();
                }
                ChatClientResponse response = iterator.next();
                ChatResponse chatResponse = response.chatResponse();
                if (chatResponse == null || chatResponse.getResult() == null
                        || chatResponse.getResult().getOutput() == null) {
                    continue;
                }
                captureUsage(usageRef, chatResponse);
                String delta = chatResponse.getResult().getOutput().getText();
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    env.messageSink().accept(delta);
                }
            }
        } else {
            ChatResponse chatResponse = spec.call().chatResponse();
            if (chatResponse == null || chatResponse.getResult() == null
                    || chatResponse.getResult().getOutput() == null) {
                throw new ServiceException(500, "节点「" + node.title() + "」模型返回为空");
            }
            captureUsage(usageRef, chatResponse);
            String content = chatResponse.getResult().getOutput().getText();
            text.append(content == null ? "" : content);
        }

        ctx.setOutput(node.id(), "text", text.toString());
        if (passthrough) {
            ctx.markStreamed(node.id());
        }
        recordUsage(node, env, modelId, usageRef[0], System.currentTimeMillis() - start, null);
    }

    private void captureUsage(Usage[] usageRef, ChatResponse chatResponse) {
        if (chatResponse.getMetadata() instanceof ChatResponseMetadata metadata
                && metadata.getUsage() != null
                && metadata.getUsage().getTotalTokens() != null
                && metadata.getUsage().getTotalTokens() > 0) {
            usageRef[0] = metadata.getUsage();
        }
    }

    private void recordUsage(FlowNode node, FlowRunEnv env, Long modelId, Usage usage,
                             long durationMs, String error) {
        if (usage == null && error == null) {
            return;
        }
        try {
            String model = modelId == null ? "default"
                    : modelService.getById(modelId).getChatModel();
            usageRecorder.record(env.tenantId(), env.userId(), env.channel(), env.agentCode(), model,
                    env.sessionId(), usage, durationMs, error == null, error);
        } catch (Exception e) {
            log.debug("[mse-ai] 编排节点用量记录失败: {}", e.getMessage());
        }
    }

    /** 供错误路径记账（引擎捕获异常后调用；预留，当前异常时无用量可记） */
    @SuppressWarnings("unused")
    private void recordFailure(FlowNode node, FlowRunEnv env, Long modelId, long durationMs, String error) {
        recordUsage(node, env, modelId, null, durationMs, error);
    }
}
