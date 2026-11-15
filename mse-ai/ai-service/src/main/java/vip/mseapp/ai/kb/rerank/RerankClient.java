package vip.mseapp.ai.kb.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rerank 重排客户端：OpenAI 兼容 /rerank 协议（硅基流动 / Jina / Cohere v2 及
 * bge-reranker 类本地推理服务均为此形态）。
 *
 * <p>POST {base-url}/rerank，请求 {model, query, documents, top_n}，
 * 响应 {results: [{index, relevance_score}]}。端点参数（base-url / api-key / 模型名）
 * 由调用方经模型中心 rerank 行解析传入（知识库绑定 > 旧版平台级配置，见
 * AiModelService#resolveRerankEndpoint），本组件只负责按端点发起调用。
 * 调用失败一律抛出业务异常，由调用方降级为不重排（返回原始顺序），不阻断检索管线。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);

    /** RestClient 按 baseUrl 复用（key/模型名按次传入） */
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    /** 重排端点（模型中心 rerank 行或旧版平台级配置的归一形态） */
    public record RerankEndpoint(String baseUrl, String apiKey, String model, String name) {
    }

    /** 重排结果 */
    public record RerankResult(int index, double score) {
    }

    /**
     * 对候选文档按与 query 的相关性重排。
     *
     * @param endpoint  重排端点（base-url / key / 模型名）
     * @param documents 候选文本（与候选列表同序）
     * @param topN      保留条数
     * @return 按 relevance_score 降序的结果（index 指向 documents 下标）
     * @throws ServiceException 端点不可用/响应不合法（调用方负责降级）
     */
    public List<RerankResult> rerank(RerankEndpoint endpoint, String query, List<String> documents, int topN) {
        try {
            RerankResponse response = clientOf(endpoint).post()
                    .uri("rerank")
                    .header("Authorization", "Bearer " + (endpoint.apiKey() == null ? "" : endpoint.apiKey()))
                    .body(new RerankRequest(endpoint.model(), query, documents, topN))
                    .retrieve()
                    .body(RerankResponse.class);
            if (response == null || response.getResults() == null) {
                throw new ServiceException(500, "rerank 响应为空");
            }
            return response.getResults().stream()
                    .map(item -> new RerankResult(item.getIndex(),
                            item.getRelevanceScore() == null ? 0d : item.getRelevanceScore()))
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .toList();
        } catch (Exception e) {
            log.warn("[mse-ai] rerank 调用失败（endpoint={}），降级为未重排: {}",
                    endpoint.name(), e.getMessage());
            throw new ServiceException(500, "rerank 调用失败：" + e.getMessage());
        }
    }

    private RestClient clientOf(RerankEndpoint endpoint) {
        if (endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()) {
            throw new ServiceException(400, "重排端点未配置 base-url");
        }
        String base = endpoint.baseUrl().endsWith("/") ? endpoint.baseUrl() : endpoint.baseUrl() + "/";
        return clients.computeIfAbsent(base, b -> RestClient.builder().baseUrl(b).build());
    }

    record RerankRequest(String model, String query, List<String> documents,
                         @JsonProperty("top_n") int topN) {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RerankResponse {
        private List<Item> results;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Item {
        private int index;
        @JsonProperty("relevance_score")
        private Double relevanceScore;
    }
}
