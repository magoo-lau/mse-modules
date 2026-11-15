package vip.mseapp.ai.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vip.mseapp.ai.entity.AiKbDocument;
import vip.mseapp.ai.entity.AiKnowledgeBase;
import vip.mseapp.ai.kb.rerank.RerankClient;
import vip.mseapp.ai.kb.vector.KbVectorClient;
import vip.mseapp.ai.mapper.AiKbDocumentMapper;
import vip.mseapp.ai.mapper.AiKnowledgeBaseMapper;
import vip.mseapp.ai.service.AiPlatformProperties;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库检索管线（唯一入口）：混合召回 → rerank 精排 → 阈值过滤 → 来源补全。
 *
 * <p>召回走 {@link KbVectorClient}：稠密（向量余弦）+ 稀疏（BM25 全文）经服务端
 * RRFRanker 融合（Milvus 2.5+，低版本自动纯稠密）；知识库按各自绑定的向量模型分组，
 * 逐模型集合召回后按融合分合并取候选（topK 的 3 倍），解析到重排端点（知识库绑定 rerank 行
 * > 旧版平台级配置 mse.ai.rerank.*，多库绑定时取第一个生效绑定）则精排后取 topK，
 * 否则融合分直接排序取 topK；阈值（相似度阈值）作用于最终得分。
 * 结果补全文档文件名，供引用溯源与检索测试展示。
 *
 * <p>所有数据库访问以显式 tenantId 经 {@link TenantContext#runAs} 恢复租户——
 * 调用方（智能体 Advisor / 异步线程）可能不在请求线程。
 */
@Service
public class KbRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KbRetrievalService.class);

    /** 候选放大倍数（召回 topK*3，给 rerank 与阈值过滤留冗余） */
    private static final int CANDIDATE_FACTOR = 3;
    private static final int DB_IN_BATCH_SIZE = 500;

    private final ObjectProvider<KbVectorClient> vectorClient;
    private final ObjectProvider<RerankClient> rerankClient;
    private final AiKbDocumentMapper documentMapper;
    private final AiKnowledgeBaseMapper kbMapper;
    private final AiPlatformProperties properties;
    private final vip.mseapp.ai.service.AiModelService modelService;

    /** 检索命中（score=最终排序分；rerankScore=重排模型相关性，未重排为 null） */
    public record KbHit(String chunkId, Long docId, String fileName, double score,
                        Double rerankScore, String content) {
    }

    /** 检索结果（hits + 实际生效的检索参数，供前端展示与调试） */
    public record RetrievalResult(List<KbHit> hits, String filter, int requestedTopK,
                                  boolean reranked, boolean fullText) {
    }

    public KbRetrievalService(ObjectProvider<KbVectorClient> vectorClient,
                              ObjectProvider<RerankClient> rerankClient,
                              AiKbDocumentMapper documentMapper, AiKnowledgeBaseMapper kbMapper,
                              AiPlatformProperties properties,
                              vip.mseapp.ai.service.AiModelService modelService) {
        this.vectorClient = vectorClient;
        this.rerankClient = rerankClient;
        this.documentMapper = documentMapper;
        this.kbMapper = kbMapper;
        this.properties = properties;
        this.modelService = modelService;
    }

    /**
     * 检索。
     *
     * @param tenantId  租户（过滤与数据访问均以此收口）
     * @param kbIds     知识库范围（空 = 全部租户内知识库）
     * @param query     查询文本
     * @param topK      返回条数
     * @param threshold 相似度阈值（null 时用 0.5 默认值）
     */
    public RetrievalResult retrieve(long tenantId, List<Long> kbIds, String query,
                                    int topK, Double threshold) {
        KbVectorClient client = requireVectorClient();
        double minScore = threshold == null ? 0.5 : Math.max(0d, Math.min(1d, threshold));

        // 1) 混合召回（服务端 RRF 融合）：知识库按各自绑定的向量模型分组、逐模型集合召回后合并
        Map<Long, AiKnowledgeBase> knowledgeBases = loadKnowledgeBases(tenantId, kbIds);
        List<Long> scope = kbIds == null || kbIds.isEmpty()
                ? new ArrayList<>(knowledgeBases.keySet()) : kbIds;
        int candidateK = Math.min(topK * CANDIDATE_FACTOR, 64);
        List<KbVectorClient.KbVectorHit> candidates = recall(client, tenantId, scope, knowledgeBases, query, candidateK);

        // 2) rerank 精排（端点：知识库绑定 > 旧版平台级配置；失败降级为融合分排序）
        List<KbHit> hits = new ArrayList<>();
        RerankClient rerank = rerankClient.getIfAvailable();
        RerankClient.RerankEndpoint rerankEndpoint = resolveRerankEndpoint(scope, knowledgeBases);
        boolean reranked = false;
        if (rerank != null && rerankEndpoint != null && candidates.size() > 1) {
            try {
                List<RerankClient.RerankResult> ranked = rerank.rerank(rerankEndpoint, query,
                        candidates.stream().map(KbVectorClient.KbVectorHit::content).toList(),
                        topK);
                reranked = true;
                for (RerankClient.RerankResult item : ranked) {
                    if (item.index() < 0 || item.index() >= candidates.size()) {
                        continue;
                    }
                    KbVectorClient.KbVectorHit candidate = candidates.get(item.index());
                    if (item.score() < minScore) {
                        continue;
                    }
                    hits.add(new KbHit(candidate.id(), candidate.docId(), null,
                            item.score(), item.score(), candidate.content()));
                }
            } catch (Exception e) {
                log.warn("[mse-ai] rerank 失败，回落 RRF 融合分: {}", e.getMessage());
                hits.clear();
            }
        }
        if (!reranked) {
            hits = candidates.stream()
                    .filter(candidate -> candidate.score() >= minScore)
                    .limit(topK)
                    .map(candidate -> new KbHit(candidate.id(), candidate.docId(), null,
                            candidate.score(), null, candidate.content()))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        // 3) 补全文件名（引用溯源/检索测试展示）
        fillFileNames(tenantId, hits);
        return new RetrievalResult(hits, buildFilter(tenantId, kbIds), topK, reranked, client.fullTextSupported());
    }

    /** 重排端点解析：范围内第一个显式绑定 rerank 行的知识库生效；未绑定回落服务级配置；均无 = null（不重排） */
    private RerankClient.RerankEndpoint resolveRerankEndpoint(
            List<Long> scope, Map<Long, AiKnowledgeBase> knowledgeBases) {
        for (Long kbId : scope) {
            AiKnowledgeBase kb = knowledgeBases.get(kbId);
            if (kb != null && kb.getRerankModelId() != null) {
                
                RerankClient.RerankEndpoint endpoint =
                        modelService.resolveRerankEndpoint(kb.getRerankModelId());
                if (endpoint != null) {
                    return endpoint;
                }
            }
        }
        return modelService.resolveRerankEndpoint(null);
    }

    /**
     * 按向量模型分组召回：知识库 → 生效向量模型（绑定 > 旧版全局配置 > 平台默认）分组，
     * 逐模型集合检索后按融合分倒序合并截断到候选数。范围内任一知识库未解析到向量模型则报业务错误；
     * 显式绑定实际生效时校验归属（模型行世界自有，跨世界绑定即拦截）。
     */
    private List<KbVectorClient.KbVectorHit> recall(KbVectorClient client, long tenantId,
                                                    List<Long> scope, Map<Long, AiKnowledgeBase> knowledgeBases,
                                                    String query, int candidateK) {
        Map<Long, List<Long>> byModel = new java.util.LinkedHashMap<>();
        for (Long kbId : scope) {
            AiKnowledgeBase kb = knowledgeBases.get(kbId);
            Long binding = kb == null ? null : kb.getEmbeddingModelId();
            Long modelId = modelService.resolveEmbeddingModelId(binding);
            if (binding != null && binding.equals(modelId)) {
                modelService.getById(binding);
            }
            if (modelId == null) {
                throw new ServiceException(400, "知识库 " + (kb == null ? kbId : kb.getName())
                        + " 未绑定可用的向量模型：请在模型中心设置默认向量模型或为知识库选择向量模型");
            }
            byModel.computeIfAbsent(modelId, k -> new ArrayList<>()).add(kbId);
        }
        List<KbVectorClient.KbVectorHit> candidates = new ArrayList<>();
        for (Map.Entry<Long, List<Long>> entry : byModel.entrySet()) {
            candidates.addAll(client.search(entry.getKey(), tenantId, query,
                    buildFilter(tenantId, entry.getValue()), candidateK));
        }
        candidates.sort(java.util.Comparator.comparingDouble(KbVectorClient.KbVectorHit::score).reversed());
        return candidates.size() > candidateK ? new ArrayList<>(candidates.subList(0, candidateK)) : candidates;
    }

    /** 按知识库配置解析检索参数（智能体多库绑定时：topK 取最大、阈值取最小最宽松） */
    public RetrievalParams resolveParams(long tenantId, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return new RetrievalParams(4, null);
        }
        Map<Long, AiKnowledgeBase> knowledgeBases = loadKnowledgeBases(tenantId, kbIds);
        int topK = 0;
        Double threshold = null;
        for (Long kbId : kbIds) {
            AiKnowledgeBase kb = knowledgeBases.get(kbId);
            if (kb == null) {
                continue;
            }
            topK = Math.max(topK, kb.getTopK() == null ? 4 : kb.getTopK());
            if (kb.getSimilarityThreshold() != null) {
                threshold = threshold == null ? kb.getSimilarityThreshold()
                        : Math.min(threshold, kb.getSimilarityThreshold());
            }
        }
        return new RetrievalParams(topK > 0 ? topK : 4, threshold);
    }

    public record RetrievalParams(int topK, Double threshold) {
    }

    /** 批量读取检索范围内知识库，避免每个知识库单独查一次。 */
    private Map<Long, AiKnowledgeBase> loadKnowledgeBases(long tenantId, List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return TenantContext.runAs(tenantId, () -> {
                Map<Long, AiKnowledgeBase> result = new java.util.LinkedHashMap<>();
                for (AiKnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<>())) {
                    result.put(kb.getId(), kb);
                }
                return result;
            });
        }
        List<Long> ids = kbIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return new java.util.HashMap<>();
        }
        return TenantContext.runAs(tenantId, () -> {
            Map<Long, AiKnowledgeBase> result = new java.util.HashMap<>();
            for (int from = 0; from < ids.size(); from += DB_IN_BATCH_SIZE) {
                List<Long> batch = ids.subList(from, Math.min(from + DB_IN_BATCH_SIZE, ids.size()));
                for (AiKnowledgeBase kb : kbMapper.selectList(new LambdaQueryWrapper<AiKnowledgeBase>()
                        .in(AiKnowledgeBase::getId, batch))) {
                    result.put(kb.getId(), kb);
                }
            }
            return result;
        });
    }

    private String buildFilter(long tenantId, List<Long> kbIds) {
        StringBuilder filter = new StringBuilder("tenantId == ").append(tenantId);
        if (kbIds != null && !kbIds.isEmpty()) {
            filter.append(" && kbId in [")
                    .append(kbIds.stream().map(String::valueOf).collect(Collectors.joining(", ")))
                    .append("]");
        }
        return filter.toString();
    }

    private void fillFileNames(long tenantId, List<KbHit> hits) {
        List<Long> docIds = hits.stream().map(KbHit::docId).filter(id -> id != null && id > 0).distinct().toList();
        if (docIds.isEmpty()) {
            return;
        }
        Map<Long, String> names = TenantContext.runAs(tenantId, () ->
                        documentMapper.selectBatchIds(docIds)).stream()
                .collect(Collectors.toMap(AiKbDocument::getId, AiKbDocument::getFileName));
        for (int i = 0; i < hits.size(); i++) {
            KbHit hit = hits.get(i);
            String fileName = names.get(hit.docId());
            if (fileName != null) {
                hits.set(i, new KbHit(hit.chunkId(), hit.docId(), fileName, hit.score(),
                        hit.rerankScore(), hit.content()));
            }
        }
    }

    public KbVectorClient requireVectorClient() {
        KbVectorClient client = vectorClient.getIfAvailable();
        if (client == null) {
            throw new ServiceException(400, "向量库未部署，无法向量化与检索");
        }
        return client;
    }
}
