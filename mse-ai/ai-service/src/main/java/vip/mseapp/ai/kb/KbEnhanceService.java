package vip.mseapp.ai.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.service.AiModelFactory;
import vip.mseapp.ai.service.AiPlatformProperties;
import vip.mseapp.ai.support.AiJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库切片索引增强（LLM 自动关键词/自动问题）：解析入库时对每个切片调用对话模型生成
 * 关键词与「用户可能会问的问题」，随切片落库（可展示/可复嵌），并前置拼入稠密向量嵌入文本
 * （假设问题索引，提升问句式 query 召回）；BM25 与检索展示仍用原文，不污染引用来源。
 *
 * <p>模型选择：mse.ai.kb.enhance-model-id > spring.ai.* 默认模型。失败降级——增强失败不阻断
 * 解析管线（该切片按原文嵌入），调用方以返回的空结果继续。
 */
@Slf4j
@Service
public class KbEnhanceService {

    /** 送入 LLM 的切片正文上限（超出截断；嵌入仍用全文） */
    private static final int PROMPT_CONTENT_LIMIT = 3000;
    /** 问题数上限（防配置过大拖垮解析） */
    private static final int MAX_QUESTIONS = 5;
    /** 关键词个数（固定区间，模型自行取舍） */
    private static final int KEYWORD_COUNT = 6;

    private final AiModelFactory modelFactory;
    private final AiPlatformProperties properties;

    public KbEnhanceService(AiModelFactory modelFactory, AiPlatformProperties properties) {
        this.modelFactory = modelFactory;
        this.properties = properties;
    }

    /**
     * 生成关键词与自动问题；任何失败返回空结果（调用方按原文嵌入，解析管线不中断）。
     *
     * @param content       切片正文
     * @param questionCount 期望问题数（1~5，超界收敛）
     * @param tenantId      租户（模型租户覆盖合并）
     */
    public EnhanceResult enhance(String content, int questionCount, Long tenantId) {
        if (content == null || content.isBlank()) {
            return EnhanceResult.empty();
        }
        int questions = Math.min(Math.max(questionCount, 1), MAX_QUESTIONS);
        try {
            ChatModel chatModel = modelFactory.getChatModel(properties.getKbEnhanceModelId());
            String prompt = "你是知识库索引增强助手。阅读下面的文本片段，生成检索增强用的关键词与用户可能会问的自然问句。\n"
                    + "要求：keywords 为 " + KEYWORD_COUNT + " 个左右的短词/术语（保留原文关键名词）；"
                    + "questions 为 " + questions + " 条用户会拿去检索的完整问句（中文）。\n"
                    + "返回且仅返回一个 JSON 对象：{\"keywords\":[\"…\"],\"questions\":[\"…\"]}，不要输出其他内容。\n\n"
                    + "文本片段：" + truncate(content);
            String answer = ChatClient.builder(chatModel).build().prompt().user(prompt)
                    .options(ChatOptions.builder().temperature(0.0))
                    .call().content();
            return parse(answer);
        } catch (Exception e) {
            log.warn("[mse-ai] 切片索引增强失败（按原文嵌入）: {}", e.getMessage());
            return EnhanceResult.empty();
        }
    }

    /** 由切片落库的 JSON 列重建嵌入文本（重建索引复用，不重复调用 LLM） */
    public static String composeEmbedText(String content, String keywordsJson, String questionsJson) {
        List<String> keywords = parseList(keywordsJson);
        List<String> questions = parseList(questionsJson);
        return compose(content, keywords, questions);
    }

    /** 宽容解析落库 JSON 数组串（非法/为空返回空表） */
    private static List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return toStringList(AiJson.MAPPER.readValue(json, Object.class), 100);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 嵌入文本组装：假设问题 + 关键词前置 + 原文（空增强时原文原样） */
    private static String compose(String content, List<String> keywords, List<String> questions) {
        if (keywords.isEmpty() && questions.isEmpty()) {
            return content;
        }
        StringBuilder sb = new StringBuilder();
        questions.forEach(q -> sb.append("问：").append(q).append('\n'));
        if (!keywords.isEmpty()) {
            sb.append("关键词：").append(String.join("、", keywords)).append('\n');
        }
        return sb.append(content).toString();
    }

    /** 宽容解析：剥 markdown 栅栏取首个 JSON 对象；解析失败返回空结果 */
    private EnhanceResult parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return EnhanceResult.empty();
        }
        String text = answer.trim()
                .replaceAll("^```(json)?\\s*", "")
                .replaceAll("\\s*```$", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return EnhanceResult.empty();
        }
        try {
            Map<String, Object> map = AiJson.toMap(text.substring(start, end + 1));
            List<String> keywords = toStringList(map.get("keywords"), KEYWORD_COUNT + 4);
            List<String> questions = toStringList(map.get("questions"), MAX_QUESTIONS);
            return new EnhanceResult(keywords, questions);
        } catch (Exception e) {
            log.warn("[mse-ai] 切片索引增强输出解析失败（按原文嵌入）: {}", e.getMessage());
            return EnhanceResult.empty();
        }
    }

    private static List<String> toStringList(Object value, int limit) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<String> list = raw.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .limit(limit)
                .collect(Collectors.toCollection(ArrayList::new));
        return list.isEmpty() ? List.of() : List.copyOf(list);
    }

    private static String truncate(String content) {
        return content.length() <= PROMPT_CONTENT_LIMIT ? content : content.substring(0, PROMPT_CONTENT_LIMIT);
    }

    /** 增强结果（keywords/questions 空表示未增强或失败降级） */
    public record EnhanceResult(List<String> keywords, List<String> questions) {

        public static EnhanceResult empty() {
            return new EnhanceResult(List.of(), List.of());
        }

        public boolean isEmpty() {
            return keywords.isEmpty() && questions.isEmpty();
        }

        /** 落库 JSON（ai_kb_chunk.keywords / questions 列，重建索引时反解析复嵌） */
        public String keywordsJson() {
            return isEmpty() ? null : AiJson.toJson(keywords);
        }

        public String questionsJson() {
            return isEmpty() ? null : AiJson.toJson(questions);
        }

        /** 嵌入文本（假设问题 + 关键词前置 + 原文；展示与 BM25 仍用原文） */
        public String embedText(String content) {
            return compose(content, keywords, questions);
        }
    }
}
