package vip.mseapp.ai.kb.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import vip.mseapp.ai.service.AiModelFactory;
import vip.mseapp.ai.service.AiPlatformProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库向量客户端：Milvus V2 SDK 直连，稠密 + BM25 稀疏双通道混合检索。
 *
 * <p>按向量模型分集合路由（知识库可各自绑定向量模型）：集合 {@code mse_ai_kb_<modelId>}，
 * 旧版全局集合 {@code mse_ai_kb_v2} 仍由 mse.ai.embedding-model-id 指向的模型沿用（存量向量零迁移）。
 * 集合 schema：id（雪花字符串主键，与 ai_kb_chunk.id 一致）、content（中文 analyzer，
 * 服务端 BM25 Function 自动生成 sparse 稀疏向量）、dense（稠密向量，维度 = 首次建集合时
 * 按该模型实测 embedding 维度）、标量 tenantId/kbId/docId（检索过滤表达式实现租户与知识库隔离）。
 * embedding 实例经 {@link AiModelFactory#getEmbeddingModel(Long)} 按模型 id 取用（工厂内缓存）。
 *
 * <p>BM25 全文检索要求 Milvus 2.5+：低版本服务端自动降级纯向量通道
 * （{@link #fullTextSupported()}），前端状态接口据此提示升级。
 */
public class KbVectorClient {

    private static final Logger log = LoggerFactory.getLogger(KbVectorClient.class);

    /** 旧版全局集合（mse.ai.embedding-model-id 指向的模型沿用，存量向量保留于此） */
    public static final String LEGACY_COLLECTION = "mse_ai_kb_v2";
    /** 按向量模型分集合的前缀：mse_ai_kb_<modelId> */
    private static final String COLLECTION_PREFIX = "mse_ai_kb_";

    private static final String F_ID = "id";
    private static final String F_CONTENT = "content";
    private static final String F_DENSE = "dense";
    private static final String F_SPARSE = "sparse";
    private static final String F_TENANT = "tenantId";
    private static final String F_KB = "kbId";
    private static final String F_DOC = "docId";

    /** 插入批大小（Milvus 单批行数上限保护） */
    private static final int INSERT_BATCH = 100;

    /** 懒连接持有（首次使用时建连；null=尚未连接） */
    private volatile MilvusClientV2 client;
    private final AiModelFactory modelFactory;
    private final AiPlatformProperties properties;

    /** 模型实测向量维度缓存（建集合时探测一次） */
    private final Map<Long, Integer> dimensionCache = new ConcurrentHashMap<>();
    /** 服务端 BM25 全文检索支持（2.5+），懒探测 */
    private volatile Boolean fullText;

    /** 检索命中（score 为 RRF 融合分或纯稠密余弦分） */
    public record KbVectorHit(String id, float score, String content, long docId) {
    }

    public KbVectorClient(AiPlatformProperties properties, AiModelFactory modelFactory) {
        this.properties = properties;
        this.modelFactory = modelFactory;
        // 懒连接（0.1.0 回归修正）：MilvusClientV2 构造器 eager 建连（gRPC wait_for_ready 约 10s 超时
        // 即抛），Milvus 未部署时阻塞整个服务启动——与「未部署保持可启动（RAG 降级提示）」的装配承诺
        // 矛盾。改为首次使用时建连：启动不触碰 Milvus，RAG 操作时连接失败按业务异常降级。
        log.info("[mse-ai] Milvus 向量客户端懒初始化: {}（首次使用时建连，按向量模型分集合路由）",
                properties.getMilvusHost() + ":" + properties.getMilvusPort());
    }

    /** 首次使用建连（双检）；连接失败抛业务异常（RAG 降级，不阻断服务） */
    private MilvusClientV2 client() {
        MilvusClientV2 current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = client;
            if (current != null) {
                return current;
            }
            try {
                current = new MilvusClientV2(ConnectConfig.builder()
                        .uri("http://" + properties.getMilvusHost() + ":" + properties.getMilvusPort())
                        .build());
                client = current;
                log.info("[mse-ai] Milvus 向量客户端已连接: {}", properties.getMilvusHost() + ":" + properties.getMilvusPort());
                return current;
            } catch (Exception e) {
                throw new vip.mseapp.core.exception.ServiceException(1200,
                        "向量库不可达（" + properties.getMilvusHost() + ":" + properties.getMilvusPort()
                                + "），RAG 功能降级：" + e.getMessage());
            }
        }
    }

    // ==================== 集合路由 ====================

    /** 集合路由：旧版全局配置指向的模型沿用 mse_ai_kb_v2（存量向量零迁移），其余按模型独立集合 */
    public String collectionFor(Long modelId) {
        Long legacyId = properties.getEmbeddingModelId();
        if (legacyId != null && legacyId.equals(modelId)) {
            return LEGACY_COLLECTION;
        }
        return COLLECTION_PREFIX + modelId;
    }

    /** 集合不存在则创建（维度按模型实测；schema + 索引 + load）；已存在视为就绪 */
    private void ensureCollection(String collection, Long modelId) {
        Boolean exists = client().hasCollection(HasCollectionReq.builder().collectionName(collection).build());
        if (Boolean.TRUE.equals(exists)) {
            return;
        }
        int dimensions = probeDimensions(modelId);
        CreateCollectionReq.CollectionSchema schema = client().createSchema();
        schema.addField(AddFieldReq.builder().fieldName(F_ID).dataType(DataType.VarChar)
                .maxLength(64).isPrimaryKey(true).build());
        schema.addField(AddFieldReq.builder().fieldName(F_CONTENT).dataType(DataType.VarChar)
                .maxLength(65535).enableAnalyzer(true)
                .analyzerParams(Map.of("type", "chinese")).build());
        schema.addField(AddFieldReq.builder().fieldName(F_DENSE).dataType(DataType.FloatVector)
                .dimension(dimensions).build());
        if (fullTextSupported()) {
            schema.addField(AddFieldReq.builder().fieldName(F_SPARSE).dataType(DataType.SparseFloatVector).build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(io.milvus.common.clientenum.FunctionType.BM25)
                    .name("content_bm25_fn")
                    .inputFieldNames(List.of(F_CONTENT))
                    .outputFieldNames(List.of(F_SPARSE))
                    .build());
        }
        schema.addField(AddFieldReq.builder().fieldName(F_TENANT).dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName(F_KB).dataType(DataType.Int64).build());
        schema.addField(AddFieldReq.builder().fieldName(F_DOC).dataType(DataType.Int64).build());

        client().createCollection(CreateCollectionReq.builder()
                .collectionName(collection).collectionSchema(schema).build());

        List<IndexParam> indexParams = new ArrayList<>();
        indexParams.add(IndexParam.builder().fieldName(F_DENSE)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE).build());
        if (fullTextSupported()) {
            indexParams.add(IndexParam.builder().fieldName(F_SPARSE)
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.BM25).build());
        }
        client().createIndex(CreateIndexReq.builder().collectionName(collection).indexParams(indexParams).build());
        client().loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
        log.info("[mse-ai] Milvus 集合已创建: collection={}, model={}, 维度 {}，全文检索 {}",
                collection, modelId, dimensions, fullTextSupported() ? "启用（BM25 混合检索）" : "降级纯向量（Milvus < 2.5）");
    }

    /** 实测模型向量维度（embed 一条样例文本；结果缓存） */
    private int probeDimensions(Long modelId) {
        return dimensionCache.computeIfAbsent(modelId, id -> {
            float[] sample = modelFactory.getEmbeddingModel(id).embed("维度探测");
            log.info("[mse-ai] 向量模型维度实测: model={}, dimensions={}", id, sample.length);
            return sample.length;
        });
    }

    // ==================== 写入与删除 ====================

    /** 批量插入（向量在客户端由该知识库绑定的向量模型生成；sparse 由服务端 BM25 Function 自动生成；tenantId 用于数据过滤与路由） */
    public void insert(Long modelId, Long tenantId, List<KbVectorDoc> docs) {
        String collection = requireCollection(modelId);
        EmbeddingModel embeddingModel = modelFactory.getEmbeddingModel(modelId);
        // 逐条向量化（Spring AI 模型实例线程安全），批量写 Milvus；
        // 稠密嵌入用 embedText（索引增强文本，空回退原文），content 存原文供 BM25 与展示
        List<JsonObject> rows = new ArrayList<>(docs.size());
        for (int i = 0; i < docs.size(); i++) {
            KbVectorDoc doc = docs.get(i);
            String embedInput = doc.embedText() == null || doc.embedText().isBlank()
                    ? doc.content() : doc.embedText();
            float[] vector = embeddingModel.embed(embedInput);
            JsonObject row = new JsonObject();
            row.addProperty(F_ID, doc.id());
            row.addProperty(F_CONTENT, doc.content());
            JsonArray dense = new JsonArray(vector.length);
            for (float v : vector) {
                dense.add(v);
            }
            row.add(F_DENSE, dense);
            row.addProperty(F_TENANT, doc.tenantId());
            row.addProperty(F_KB, doc.kbId());
            row.addProperty(F_DOC, doc.docId());
            rows.add(row);
        }
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            client().insert(InsertReq.builder().collectionName(collection)
                    .data(rows.subList(from, Math.min(rows.size(), from + INSERT_BATCH)))
                    .build());
        }
    }

    /** 按切片 id（向量主键）删除（该模型当前集合） */
    public void delete(Long modelId, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String collection = requireCollection(modelId);
        client().delete(DeleteReq.builder().collectionName(collection)
                .ids(new ArrayList<>(ids)).build());
    }

    /**
     * 按切片 id 清理向量（跨候选集合兜底遍历：知识库改绑模型后旧向量仍在原模型集合，
     * 常规删除只清当前集合会留脏数据；集合不存在自动跳过）。
     */
    public void deleteEverywhere(List<Long> candidateModelIds, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String collection : candidateCollections(candidateModelIds)) {
            try {
                client().delete(DeleteReq.builder().collectionName(collection)
                        .ids(new ArrayList<>(ids)).build());
            } catch (Exception e) {
                log.debug("[mse-ai] 向量清理跳过集合 {}: {}", collection, e.getMessage());
            }
        }
    }

    /** 按知识库过滤式清空向量（重建索引前全集合兜底清场；集合不存在自动跳过） */
    public void deleteKbEverywhere(List<Long> candidateModelIds, long kbId) {
        for (String collection : candidateCollections(candidateModelIds)) {
            try {
                client().delete(DeleteReq.builder().collectionName(collection)
                        .filter(F_KB + " == " + kbId).build());
            } catch (Exception e) {
                log.debug("[mse-ai] 向量清场跳过集合 {}: {}", collection, e.getMessage());
            }
        }
    }

    /** 候选集合 = 旧版全局集合 + 各候选模型集合（去重保序） */
    private LinkedHashSet<String> candidateCollections(List<Long> candidateModelIds) {
        LinkedHashSet<String> collections = new LinkedHashSet<>();
        collections.add(LEGACY_COLLECTION);
        if (candidateModelIds != null) {
            candidateModelIds.stream().filter(id -> id != null && id > 0)
                    .forEach(id -> collections.add(COLLECTION_PREFIX + id));
        }
        return collections;
    }

    // ==================== 检索 ====================

    /**
     * 混合检索：稠密（向量余弦）+ 稀疏（BM25 全文）双通道 RRF 融合；
     * 服务端 < 2.5 自动降级纯稠密检索。
     *
     * @param modelId  向量模型 id（决定集合与 embedding 实例）
     * @param tenantId 租户（数据过滤与路由用）
     * @param filter   Milvus 标量过滤表达式（如 "tenantId == 1 && kbId in [2, 3]"）
     * @param topK     返回条数
     */
    public List<KbVectorHit> search(Long modelId, Long tenantId, String query, String filter, int topK) {
        String collection = requireCollection(modelId);
        EmbeddingModel embeddingModel = modelFactory.getEmbeddingModel(modelId);
        float[] queryVector = embeddingModel.embed(query);
        List<Float> denseVector = new ArrayList<>(queryVector.length);
        for (float v : queryVector) {
            denseVector.add(v);
        }
        AnnSearchReq denseReq = AnnSearchReq.builder()
                .vectorFieldName(F_DENSE)
                .vectors(List.of(new FloatVec(denseVector)))
                .topK(topK)
                .filter(filter)
                .build();

        SearchResp resp;
        if (fullTextSupported()) {
            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName(F_SPARSE)
                    .vectors(List.of(new EmbeddedText(query)))
                    .topK(topK)
                    .filter(filter)
                    .build();
            resp = client().hybridSearch(HybridSearchReq.builder()
                    .collectionName(collection)
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(new RRFRanker(60))
                    .topK(topK)
                    .outFields(List.of(F_CONTENT, F_DOC))
                    .build());
        } else {
            resp = client().search(SearchReq.builder()
                    .collectionName(collection)
                    .data(List.of(new FloatVec(denseVector)))
                    .topK(topK)
                    .filter(filter)
                    .outputFields(List.of(F_CONTENT, F_DOC))
                    .build());
        }
        List<KbVectorHit> hits = new ArrayList<>();
        List<SearchResp.SearchResult> results = resp.getSearchResults().get(0);
        for (SearchResp.SearchResult result : results) {
            Object content = result.getEntity().get(F_CONTENT);
            Object docId = result.getEntity().get(F_DOC);
            hits.add(new KbVectorHit(String.valueOf(result.getId()),
                    result.getScore() == null ? 0f : result.getScore(),
                    content == null ? "" : String.valueOf(content),
                    docId == null ? 0L : Long.parseLong(String.valueOf(docId))));
        }
        return hits;
    }

    /** 集合与连接可用性自检（状态接口用） */
    public void checkAvailable() {
        fullTextSupported();
    }

    /** 服务端版本 >= 2.5 才支持 BM25 Function 全文检索（懒探测一次） */
    public boolean fullTextSupported() {
        Boolean detected = fullText;
        if (detected == null) {
            synchronized (this) {
                if (fullText == null) {
                    fullText = detectFullTextSupport();
                }
                detected = fullText;
            }
        }
        return detected;
    }

    private boolean detectFullTextSupport() {
        try {
            String version = client().getServerVersion();
            String[] parts = version.replaceAll("[^0-9.]", "").split("\\.");
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 2 || (major == 2 && minor >= 5);
        } catch (Exception e) {
            log.warn("[mse-ai] Milvus 服务端版本探测失败，全文检索降级: {}", e.getMessage());
            return false;
        }
    }

    public void close() {
        MilvusClientV2 current = client;
        if (current != null) {
            current.close();
        }
    }

    /** 解析集合（确保存在），失败给业务语义错误 */
    private String requireCollection(Long modelId) {
        if (modelId == null) {
            throw new vip.mseapp.core.exception.ServiceException(400, "未指定向量模型：请在知识库设置中选择向量模型，或在模型中心设置默认向量模型");
        }
        String collection = collectionFor(modelId);
        try {
            ensureCollection(collection, modelId);
        } catch (Exception e) {
            throw new vip.mseapp.core.exception.ServiceException(500, "向量库集合不可用：" + e.getMessage());
        }
        return collection;
    }

    /**
     * 向量写入单元：content 为展示/BM25 用的原文，embedText 为稠密嵌入输入（索引增强时
     * = 假设问题+关键词前置的增强文本；空/空白回退 content）。
     */
    public record KbVectorDoc(String id, String content, String embedText, long tenantId, long kbId, long docId) {

        /** 无增强构造（嵌入文本 = 正文） */
        public KbVectorDoc(String id, String content, long tenantId, long kbId, long docId) {
            this(id, content, null, tenantId, kbId, docId);
        }
    }
}
