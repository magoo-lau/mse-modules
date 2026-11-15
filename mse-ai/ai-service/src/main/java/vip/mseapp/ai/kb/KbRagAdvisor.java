package vip.mseapp.ai.kb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 知识库检索增强 Advisor：对话前置检索（统一管线：混合召回 → rerank → 阈值），
 * 命中切片以编号引用注入用户消息，并回调消费检索来源（SSE sources 事件）。
 *
 * <p>替代 QuestionAnswerAdvisor——后者仅支持单路向量召回且无法回传引用来源。
 * 检索失败降级为不带上下文直接对话（RAG 不阻断聊天）。
 *
 * <p>来源回调：AiChatService 经 advisor 参数注入
 * {@code Consumer<List<KbRetrievalService.KbHit>>}（context key
 * {@link #SOURCES_CONSUMER}），before 阶段检索完成后回调推送 SSE sources 事件。
 */
public class KbRagAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(KbRagAdvisor.class);

    /** context key：检索来源消费者（Consumer<List<KbRetrievalService.KbHit>>） */
    public static final String SOURCES_CONSUMER = "mse.ai.kb.sourceConsumer";

    /** context key：本次对话命中的检索来源（KbRagAdvisor 写入，链路后置节点可读） */
    public static final String SOURCES_HITS = "mse.ai.kb.sourceHits";

    private static final String CONTEXT_HEADER = "以下是与用户问题相关的知识库检索结果（回答时可引用，标注 [编号]）：";

    private final KbRetrievalService retrievalService;
    private final long tenantId;
    private final List<Long> kbIds;
    private final int topK;
    private final Double threshold;
    private final int order;

    public KbRagAdvisor(KbRetrievalService retrievalService, long tenantId, List<Long> kbIds,
                        int topK, Double threshold, int order) {
        this.retrievalService = retrievalService;
        this.tenantId = tenantId;
        this.kbIds = kbIds;
        this.topK = topK;
        this.threshold = threshold;
        this.order = order;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String query = request.prompt().getUserMessage() == null ? null
                : request.prompt().getUserMessage().getText();
        if (query == null || query.isBlank()) {
            return request;
        }
        List<KbRetrievalService.KbHit> hits;
        try {
            KbRetrievalService.RetrievalResult result =
                    retrievalService.retrieve(tenantId, kbIds, query, topK, threshold);
            hits = result.hits();
        } catch (Exception e) {
            log.warn("[mse-ai] RAG 检索失败，降级直连对话: tenant={}, kbIds={}, {}",
                    tenantId, kbIds, e.getMessage());
            return request;
        }
        if (hits.isEmpty()) {
            return request;
        }

        Consumer<List<KbRetrievalService.KbHit>> consumer = request.context() == null ? null
                : (Consumer<List<KbRetrievalService.KbHit>>) request.context().get(SOURCES_CONSUMER);
        if (consumer != null) {
            consumer.accept(hits);
        }

        StringBuilder context = new StringBuilder(CONTEXT_HEADER);
        for (int i = 0; i < hits.size(); i++) {
            KbRetrievalService.KbHit hit = hits.get(i);
            context.append("\n\n[").append(i + 1).append("]");
            if (hit.fileName() != null) {
                context.append("（").append(hit.fileName()).append("）");
            }
            context.append(' ').append(hit.content());
        }

        Prompt prompt = request.prompt();
        List<org.springframework.ai.chat.messages.Message> instructions =
                new ArrayList<>(prompt.getInstructions());
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage userMessage) {
                instructions.set(i, new UserMessage(userMessage.getText() + "\n\n---\n" + context));
                break;
            }
        }
        ChatOptions options = prompt.getOptions();
        return request.mutate()
                .prompt(options == null ? new Prompt(instructions) : new Prompt(instructions, options))
                .context(SOURCES_HITS, hits)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
