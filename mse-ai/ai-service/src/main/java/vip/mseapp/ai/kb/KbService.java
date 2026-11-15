package vip.mseapp.ai.kb;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.ai.entity.AiKbChunk;
import vip.mseapp.ai.entity.AiKbDecl;
import vip.mseapp.ai.entity.AiKbDocument;
import vip.mseapp.ai.entity.AiKnowledgeBase;
import vip.mseapp.ai.kb.chunk.ChunkParams;
import vip.mseapp.ai.kb.chunk.TextChunkers;
import vip.mseapp.ai.kb.vector.KbVectorClient;
import vip.mseapp.ai.mapper.AiKbChunkMapper;
import vip.mseapp.ai.mapper.AiKbDeclMapper;
import vip.mseapp.ai.mapper.AiKbDocumentMapper;
import vip.mseapp.ai.mapper.AiKnowledgeBaseMapper;
import vip.mseapp.ai.service.AiPlatformProperties;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 知识库服务：上传归档 → 异步解析（Tika/直读 → 切片方法模板）→ 向量化入 Milvus（正文落 MySQL）。
 *
 * <p>向量 Document id = ai_kb_chunk.id（雪花），双向可追溯；检索统一走
 * {@link KbRetrievalService}（稠密 + BM25 混合召回 → rerank → 阈值过滤），
 * 按 metadata（tenantId/kbId）过滤实现租户与知识库隔离。每个知识库可绑定自己的向量模型
 * （embedding_model_id，空 = 旧版全局配置/平台默认解析），写入按库绑定的模型路由到对应集合；
 * 改绑后经 {@link #reindex(Long)} 全量重嵌入。向量库未部署时
 * 向量化与检索报业务异常，不阻断平台其他功能。
 *
 * <p>文档处理为异步两段式：上传仅落记录（status=0）+ 归档原文件后立即返回，
 * 解析线程池（aiKbParseExecutor）后台处理（1 处理中 → 2 完成 / 3 失败）；
 * 重新解析/重建索引复用同一管线。异步线程不依赖 ThreadLocal：租户 id 显式传参，
 * 数据库访问经 {@link TenantContext#runAs} 恢复。
 */
@Service
public class KbService {

    private static final Logger log = LoggerFactory.getLogger(KbService.class);

    /** 文档状态机：0 待处理 1 处理中 2 完成 3 失败 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_PROCESSING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;

    /** 直读文本的扩展名（其余走 Tika） */
    private static final List<String> TEXT_EXTS = List.of("txt", "md", "markdown", "csv", "json", "log");

    private final AiKnowledgeBaseMapper kbMapper;
    private final AiKbDocumentMapper documentMapper;
    private final AiKbChunkMapper chunkMapper;
    private final AiKbDeclMapper kbDeclMapper;
    private final KbRetrievalService retrievalService;
    private final KbStorageService storageService;
    private final AiPlatformProperties properties;
    private final vip.mseapp.ai.service.AiModelService modelService;
    private final KbEnhanceService enhanceService;
    private final ObjectProvider<ExecutorService> parseExecutor;

    public KbService(AiKnowledgeBaseMapper kbMapper, AiKbDocumentMapper documentMapper,
                     AiKbChunkMapper chunkMapper, AiKbDeclMapper kbDeclMapper,
                     KbRetrievalService retrievalService,
                     KbStorageService storageService, AiPlatformProperties properties,
                     vip.mseapp.ai.service.AiModelService modelService,
                     KbEnhanceService enhanceService,
                     @org.springframework.beans.factory.annotation.Qualifier("aiKbParseExecutor")
                     ObjectProvider<ExecutorService> parseExecutor) {
        this.kbMapper = kbMapper;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.kbDeclMapper = kbDeclMapper;
        this.retrievalService = retrievalService;
        this.storageService = storageService;
        this.properties = properties;
        this.modelService = modelService;
        this.enhanceService = enhanceService;
        this.parseExecutor = parseExecutor;
    }

    // ==================== 知识库 CRUD（租户隔离由基座租户插件保证） ====================

    public Page<AiKnowledgeBase> page(long pageNum, long pageSize, String keyword) {
        LambdaQueryWrapper<AiKnowledgeBase> wrapper = new LambdaQueryWrapper<AiKnowledgeBase>()
                .orderByDesc(AiKnowledgeBase::getId);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(AiKnowledgeBase::getName, keyword);
        }
        return kbMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    public AiKnowledgeBase getById(Long id) {
        AiKnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw new ServiceException(404, "知识库不存在");
        }
        return kb;
    }

    /**
     * 保存知识库。向量模型绑定（embeddingModelId）：显式传入必须是向量模型行；
     * 新建未传时落库平台默认向量模型（resolvePreferred），更新传 null = 不修改（MP 更新跳过空字段）。
     * 重排模型绑定（rerankModelId）：显式传入必须是重排模型行，传 null = 不修改（可选能力）。
     */
    public Long save(AiKnowledgeBase form) {
        AiKnowledgeBase kb = form.getId() == null ? new AiKnowledgeBase() : getById(form.getId());
        kb.setName(form.getName());
        kb.setDescription(form.getDescription());
        // 声明库编码通道：手工建/改库可携带 code（世界内唯一，撞键友好报错）；空值归 NULL 不占唯一键
        kb.setCode(form.getCode() == null || form.getCode().isBlank() ? null : form.getCode().trim());
        kb.setChunkSize(form.getChunkSize() == null ? 800 : form.getChunkSize());
        kb.setChunkMethod(form.getChunkMethod() == null || form.getChunkMethod().isBlank()
                ? "general" : form.getChunkMethod());
        kb.setChunkOverlap(form.getChunkOverlap() == null ? 100 : form.getChunkOverlap());
        kb.setTopK(form.getTopK() == null ? 4 : form.getTopK());
        kb.setSimilarityThreshold(form.getSimilarityThreshold() == null ? 0.5 : form.getSimilarityThreshold());
        if (form.getEmbeddingModelId() != null) {
            modelService.assertEmbeddingModel(form.getEmbeddingModelId());
            kb.setEmbeddingModelId(form.getEmbeddingModelId());
        } else if (kb.getId() == null) {
            kb.setEmbeddingModelId(modelService.resolvePreferredEmbeddingModelId());
        }
        if (form.getRerankModelId() != null) {
            modelService.assertRerankModel(form.getRerankModelId());
            kb.setRerankModelId(form.getRerankModelId());
        }
        // 索引增强（自动关键词/自动问题）：新建默认关、问题数默认 3（收敛 1~5）；更新未传字段保持原值
        if (form.getEnhanceEnabled() != null) {
            kb.setEnhanceEnabled(Integer.valueOf(1).equals(form.getEnhanceEnabled()) ? 1 : 0);
        } else if (kb.getId() == null) {
            kb.setEnhanceEnabled(0);
        }
        if (form.getEnhanceQuestions() != null) {
            kb.setEnhanceQuestions(Math.min(Math.max(form.getEnhanceQuestions(), 1), 5));
        } else if (kb.getId() == null) {
            kb.setEnhanceQuestions(3);
        }
        kb.setStatus(form.getStatus() == null ? 0 : form.getStatus());
        kb.setRemark(form.getRemark());
        try {
            if (kb.getId() == null) {
                kbMapper.insert(kb);
            } else {
                kbMapper.updateById(kb);
            }
        } catch (DataIntegrityViolationException e) {
            // uk_tenant_code：声明库编码世界内唯一（多 NULL 不受影响）
            throw new ServiceException(400, "知识库编码已存在: " + kb.getCode()
                    + "（声明库编码租户内唯一，请更换）");
        }
        return kb.getId();
    }

    public void delete(Long id) {
        getById(id);
        for (AiKbDocument document : documentMapper.selectList(new LambdaQueryWrapper<AiKbDocument>()
                .eq(AiKbDocument::getKbId, id))) {
            deleteDocument(id, document.getId());
        }
        kbMapper.deleteById(id);
    }

    // ==================== 声明库壳（业务积木 AiKbProvider 声明 → 各租户世界按编码幂等建壳） ====================

    /**
     * 按编码解析本世界知识库，库壳缺失时按声明目录（ai_kb_decl 平台行）幂等创建：
     * 默认切片/检索参数与管理页新建缺省一致，向量模型落本世界推荐行（可为空 = 运行期按
     * 全局配置/平台默认解析），文档内容仍由各租户在管理页上传（空库检索静默无结果，
     * 智能体对话不阻断）。无声明目录（提供方未部署/未推送）返回 null——调用方不绑定即可。
     */
    public Long ensureCodeKb(long world, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        AiKbDecl decl = TenantContext.runIgnore(() -> kbDeclMapper.selectOne(
                new LambdaQueryWrapper<AiKbDecl>()
                        .eq(AiKbDecl::getCode, code)
                        .eq(AiKbDecl::getTenantId, MseConstants.PLATFORM_TENANT_ID)
                        .last("LIMIT 1")));
        if (decl == null) {
            return null;
        }
        AiKnowledgeBase existing = TenantContext.runAs(world, () -> kbMapper.selectOne(
                new LambdaQueryWrapper<AiKnowledgeBase>()
                        .eq(AiKnowledgeBase::getTenantId, world)
                        .eq(AiKnowledgeBase::getCode, code)
                        .last("LIMIT 1")));
        if (existing != null) {
            return existing.getId();
        }
        try {
            AiKnowledgeBase shell = new AiKnowledgeBase();
            shell.setCode(code);
            shell.setName(decl.getName());
            shell.setDescription(decl.getDescription());
            shell.setChunkSize(800);
            shell.setChunkMethod("general");
            shell.setChunkOverlap(100);
            shell.setTopK(4);
            shell.setSimilarityThreshold(0.5);
            shell.setEmbeddingModelId(TenantContext.runAs(world,
                    () -> modelService.resolvePreferredEmbeddingModelId()));
            shell.setEnhanceEnabled(0);
            shell.setEnhanceQuestions(3);
            shell.setStatus(0);
            shell.setRemark("声明库壳（" + (decl.getSource() == null ? "local" : decl.getSource())
                    + "），文档请在管理页上传");
            TenantContext.runAs(world, () -> {
                kbMapper.insert(shell);
                return null;
            });
            log.info("[mse-ai] 声明知识库库壳创建: {}（tenant={}）", code, world);
            return shell.getId();
        } catch (DataIntegrityViolationException e) {
            // 并发撞 uk_tenant_code：回查胜者
            AiKnowledgeBase winner = TenantContext.runAs(world, () -> kbMapper.selectOne(
                    new LambdaQueryWrapper<AiKnowledgeBase>()
                            .eq(AiKnowledgeBase::getTenantId, world)
                            .eq(AiKnowledgeBase::getCode, code)
                            .last("LIMIT 1")));
            return winner == null ? null : winner.getId();
        }
    }

    /** 批量按编码解析（逐编码独立处理，不可解析的跳过）；全部无结果返回 null，否则返回 id CSV */
    public String resolveCodeKbIds(long world, List<String> codes) {
        List<String> ids = new ArrayList<>();
        for (String code : codes) {
            Long id = ensureCodeKb(world, code);
            if (id != null) {
                ids.add(String.valueOf(id));
            }
        }
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    // ==================== 文档与向量 ====================

    public Page<AiKbDocument> pageDocuments(Long kbId, long pageNum, long pageSize) {
        return documentMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<AiKbDocument>().eq(AiKbDocument::getKbId, kbId)
                        .orderByDesc(AiKbDocument::getId));
    }

    /** 上传：落记录（待处理）+ 归档原文件，立即返回；后台异步解析入库 */
    public AiKbDocument upload(Long kbId, MultipartFile file) throws IOException {
        getById(kbId);
        requireVectorClient();
        String fileName = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();

        AiKbDocument document = new AiKbDocument();
        document.setKbId(kbId);
        document.setFileName(fileName);
        document.setFileType(KbStorageService.extOf(fileName));
        document.setSizeBytes(file.getSize());
        document.setStatus(STATUS_PENDING);
        documentMapper.insert(document);

        document.setStoragePath(storageService.archive(document.getId(), fileName, file));
        documentMapper.updateById(document);

        Long docId = document.getId();
        Long tenantId = TenantContext.getRequiredTenantId();
        submitParse(tenantId, kbId, docId);
        log.info("[mse-ai] 知识库文档已排队解析: kb={}, doc={}, file={}", kbId, docId, fileName);
        return document;
    }

    /** 重新解析：读归档按当前知识库配置（切片方法/大小/重叠）全量重切重嵌入 */
    public void reparse(Long kbId, Long docId) {
        AiKbDocument document = requireDocument(kbId, docId);
        requireVectorClient();
        if (document.getStoragePath() == null || document.getStoragePath().isBlank()) {
            throw new ServiceException(400, "该文档无归档文件（历史数据），请删除后重新上传");
        }
        document.setStatus(STATUS_PENDING);
        document.setErrorMsg(null);
        documentMapper.updateById(document);
        submitParse(TenantContext.getRequiredTenantId(), kbId, docId);
    }

    /** 重建索引：知识库全部文档逐个重新解析（修改切片配置后使存量生效） */
    public int rebuild(Long kbId) {
        getById(kbId);
        List<AiKbDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<AiKbDocument>()
                .eq(AiKbDocument::getKbId, kbId));
        for (AiKbDocument document : documents) {
            reparse(kbId, document.getId());
        }
        return documents.size();
    }

    /** 异步解析入库（解析线程池执行；租户上下文显式恢复） */
    private void submitParse(Long tenantId, Long kbId, Long docId) {
        ExecutorService executor = parseExecutor.getIfAvailable();
        if (executor == null) {
            // 装配缺位兜底：同步处理（正常路径不会走到）
            TenantContext.runAs(tenantId, () -> processDocument(kbId, docId));
            return;
        }
        executor.submit(() -> TenantContext.runAs(tenantId, () -> processDocument(kbId, docId)));
    }

    /**
     * 重建索引（改绑向量模型或向量异常后使存量切片生效）：跨集合清场 →
     * 按现有切片正文全量重嵌入当前绑定模型集合，异步执行（不重解析文档、不读归档）。
     */
    public void reindex(Long kbId) {
        getById(kbId);
        requireVectorClient();
        requireEmbeddingModelId(kbId);
        Long tenantId = TenantContext.getRequiredTenantId();
        ExecutorService executor = parseExecutor.getIfAvailable();
        if (executor == null) {
            TenantContext.runAs(tenantId, () -> processReindex(kbId));
            return;
        }
        executor.submit(() -> TenantContext.runAs(tenantId, () -> processReindex(kbId)));
        log.info("[mse-ai] 知识库重建索引已排队: kb={}", kbId);
    }

    /** 重建索引执行：全候选集合按 kbId 清场（改绑前的旧模型集合一并清理）→ 全量切片重嵌入 */
    private void processReindex(Long kbId) {
        AiKnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) {
            return;
        }
        try {
            Long embeddingModelId = resolveEmbeddingModelId(kb);
            KbVectorClient client = requireVectorClient();
            client.deleteKbEverywhere(modelService.listEmbeddingCandidateIds(), kbId);
            List<AiKbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AiKbChunk>()
                    .eq(AiKbChunk::getKbId, kbId));
            if (!chunks.isEmpty()) {
                Long tenantId = TenantContext.getRequiredTenantId();
                // 复嵌沿用切片落库的增强列（自动关键词/问题）重组嵌入文本，不重复调用 LLM
                List<KbVectorClient.KbVectorDoc> docs = chunks.stream()
                        .map(c -> new KbVectorClient.KbVectorDoc(String.valueOf(c.getId()), c.getContent(),
                                KbEnhanceService.composeEmbedText(c.getContent(), c.getKeywords(), c.getQuestions()),
                                tenantId, kbId, c.getDocumentId()))
                        .toList();
                client.insert(embeddingModelId, tenantId, docs);
            }
            log.info("[mse-ai] 知识库重建索引完成: kb={}, model={}, chunks={}", kbId, embeddingModelId, chunks.size());
        } catch (Exception e) {
            log.warn("[mse-ai] 知识库重建索引失败: kb={}, {}", kbId, e.getMessage(), e);
        }
    }

    /** 解析知识库生效向量模型（绑定 > 旧版全局配置 > 本世界默认；无可用给明确业务错误） */
    private Long requireEmbeddingModelId(Long kbId) {
        AiKnowledgeBase kb = kbMapper.selectById(kbId);
        Long modelId = resolveEmbeddingModelId(kb);
        if (modelId == null) {
            throw new ServiceException(400, "未配置向量模型：请在模型中心新建向量模型并设为默认，或为该知识库选择向量模型");
        }
        return modelId;
    }

    /** 解析知识库生效向量模型（绑定 > 旧版全局配置 > 本世界默认）；显式绑定实际生效时校验租户可用性（圈定移出即拦截，默认兜底不受限） */
    private Long resolveEmbeddingModelId(AiKnowledgeBase kb) {
        Long binding = kb == null ? null : kb.getEmbeddingModelId();
        Long resolved = modelService.resolveEmbeddingModelId(binding);
        if (binding != null && binding.equals(resolved)) {
        }
        return resolved;
    }

    /** 文档解析管线：清旧 → 解析 → 切片 → 落 MySQL → 入向量（失败补偿清理） */
    void processDocument(Long kbId, Long docId) {
        AiKnowledgeBase kb = kbMapper.selectById(kbId);
        AiKbDocument document = documentMapper.selectById(docId);
        if (kb == null || document == null) {
            return;
        }
        try {
            document.setStatus(STATUS_PROCESSING);
            documentMapper.updateById(document);

            // 全量重建语义：先清旧切片与旧向量（重解析幂等）
            clearDocumentData(kbId, docId);

            byte[] bytes = storageService.read(document.getStoragePath());
            String text = isTextFile(document.getFileType())
                    ? new String(bytes, StandardCharsets.UTF_8) : parseByTika(bytes, document.getFileName());
            Resource resource = toResource(bytes, document.getFileName());
            ChunkParams params = new ChunkParams(
                    kb.getChunkSize() == null ? 800 : kb.getChunkSize(),
                    kb.getChunkOverlap() == null ? 100 : kb.getChunkOverlap());
            List<String> chunks = TextChunkers.split(kb.getChunkMethod(), text, resource, params);

            // 正文先落 MySQL（无向量不参与检索，无污染），向量后入 Milvus；失败补偿删正文
            Long tenantId = TenantContext.getRequiredTenantId();
            Long embeddingModelId = resolveEmbeddingModelId(kb);
            // 索引增强（自动关键词/自动问题）：逐切片 LLM 生成；失败熔断后续切片按原文嵌入（不阻断解析）
            boolean enhanceOn = Integer.valueOf(1).equals(kb.getEnhanceEnabled());
            int questionCount = kb.getEnhanceQuestions() == null ? 3 : kb.getEnhanceQuestions();
            boolean enhanceBroken = false;
            List<KbVectorClient.KbVectorDoc> vectorDocs = new ArrayList<>(chunks.size());
            List<AiKbChunk> rows = new ArrayList<>(chunks.size());
            int seq = 0;
            for (String content : chunks) {
                String chunkId = String.valueOf(IdWorker.getId());
                KbEnhanceService.EnhanceResult enhanced = KbEnhanceService.EnhanceResult.empty();
                if (enhanceOn && !enhanceBroken) {
                    enhanced = enhanceService.enhance(content, questionCount, tenantId);
                    if (enhanced.isEmpty()) {
                        // 首个空结果视为模型不可用/输出异常，熔断本文档剩余切片（逐个超时太浪费）
                        enhanceBroken = true;
                    }
                }
                vectorDocs.add(new KbVectorClient.KbVectorDoc(chunkId, content,
                        enhanced.isEmpty() ? null : enhanced.embedText(content), tenantId, kbId, docId));
                AiKbChunk row = new AiKbChunk();
                row.setId(Long.valueOf(chunkId));
                row.setKbId(kbId);
                row.setDocumentId(docId);
                row.setSeq(seq++);
                row.setContent(content);
                row.setKeywords(enhanced.keywordsJson());
                row.setQuestions(enhanced.questionsJson());
                rows.add(row);
            }
            rows.forEach(chunkMapper::insert);
            try {
                requireVectorClient().insert(embeddingModelId, tenantId, vectorDocs);
            } catch (Exception e) {
                rows.forEach(row -> chunkMapper.deleteById(row.getId()));
                throw e;
            }

            document.setStatus(STATUS_DONE);
            document.setChunkCount(rows.size());
            documentMapper.updateById(document);
            log.info("[mse-ai] 知识库文档入库完成: kb={}, doc={}, file={}, chunks={}",
                    kbId, docId, document.getFileName(), rows.size());
        } catch (Exception e) {
            log.warn("[mse-ai] 知识库文档解析失败: kb={}, doc={}, file={}", kbId, docId, document.getFileName(), e);
            document.setStatus(STATUS_FAILED);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            document.setErrorMsg(msg.length() > 480 ? msg.substring(0, 480) : msg);
            documentMapper.updateById(document);
        }
    }

    /** 删除文档：向量、切片、归档、记录四级清理 */
    public void deleteDocument(Long kbId, Long documentId) {
        requireDocument(kbId, documentId);
        AiKbDocument document = documentMapper.selectById(documentId);
        clearDocumentData(kbId, documentId);
        if (document != null) {
            storageService.remove(document.getStoragePath());
        }
        documentMapper.deleteById(documentId);
    }

    /** 下载归档原文件（本地目录与 mse-fs 归档串同端点分流；历史无归档数据报业务异常） */
    public void downloadOriginal(Long kbId, Long docId, jakarta.servlet.http.HttpServletResponse response)
            throws IOException {
        AiKbDocument document = requireDocument(kbId, docId);
        if (document.getStoragePath() == null || document.getStoragePath().isBlank()) {
            throw new ServiceException(400, "该文档无归档文件（历史数据），请删除后重新上传");
        }
        byte[] content = storageService.read(document.getStoragePath());
        response.setContentType("application/octet-stream");
        response.setContentLengthLong(content.length);
        String encoded = java.net.URLEncoder.encode(document.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.getOutputStream().write(content);
    }

    // ==================== 分块管理（人工干预） ====================

    public Page<AiKbChunk> pageChunks(Long kbId, Long docId, long pageNum, long pageSize) {
        requireDocument(kbId, docId);
        return chunkMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<AiKbChunk>()
                        .eq(AiKbChunk::getKbId, kbId)
                        .eq(AiKbChunk::getDocumentId, docId)
                        .orderByAsc(AiKbChunk::getSeq));
    }

    /** 手工新增切片：正文落 MySQL + 向量入库 */
    public AiKbChunk addChunk(Long kbId, Long docId, String content) {
        requireDocument(kbId, docId);
        Long embeddingModelId = requireEmbeddingModelId(kbId);
        KbVectorClient client = requireVectorClient();
        Long tenantId = TenantContext.getRequiredTenantId();

        AiKbChunk row = new AiKbChunk();
        row.setId(IdWorker.getId());
        row.setKbId(kbId);
        row.setDocumentId(docId);
        row.setSeq(nextSeq(kbId, docId));
        row.setContent(content);
        chunkMapper.insert(row);
        try {
            client.insert(embeddingModelId, tenantId, List.of(new KbVectorClient.KbVectorDoc(
                    String.valueOf(row.getId()), content, tenantId, kbId, docId)));
        } catch (Exception e) {
            chunkMapper.deleteById(row.getId());
            throw new ServiceException(500, "切片向量化失败：" + e.getMessage());
        }
        refreshChunkCount(kbId, docId);
        return row;
    }

    /** 编辑切片：更新正文 + 重嵌入（删旧向量插新向量，主键不变；跨候选集合兜底清旧） */
    public void updateChunk(Long kbId, Long chunkId, String content) {
        AiKbChunk row = chunkMapper.selectById(chunkId);
        if (row == null || !row.getKbId().equals(kbId)) {
            throw new ServiceException(404, "切片不存在");
        }
        Long embeddingModelId = requireEmbeddingModelId(kbId);
        KbVectorClient client = requireVectorClient();
        Long tenantId = TenantContext.getRequiredTenantId();
        client.deleteEverywhere(modelService.listEmbeddingCandidateIds(), List.of(String.valueOf(chunkId)));
        try {
            client.insert(embeddingModelId, tenantId, List.of(new KbVectorClient.KbVectorDoc(
                    String.valueOf(chunkId), content, tenantId, kbId, row.getDocumentId())));
        } catch (Exception e) {
            throw new ServiceException(500, "切片重嵌入失败：" + e.getMessage());
        }
        row.setContent(content);
        // 正文已变，自动关键词/问题随原文失效（显式置空——updateById 跳过 null 字段）；
        // 重建索引按原文嵌入，需要增强请重新解析文档
        chunkMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiKbChunk>()
                .eq(AiKbChunk::getId, chunkId)
                .set(AiKbChunk::getContent, content)
                .set(AiKbChunk::getKeywords, null)
                .set(AiKbChunk::getQuestions, null));
    }

    /** 删除切片：向量（跨候选集合兜底）+ 正文 + 计数 */
    public void deleteChunk(Long kbId, Long chunkId) {
        AiKbChunk row = chunkMapper.selectById(chunkId);
        if (row == null || !row.getKbId().equals(kbId)) {
            throw new ServiceException(404, "切片不存在");
        }
        requireEmbeddingModelId(kbId);
        requireVectorClient().deleteEverywhere(modelService.listEmbeddingCandidateIds(),
                List.of(String.valueOf(chunkId)));
        chunkMapper.deleteById(chunkId);
        refreshChunkCount(kbId, row.getDocumentId());
    }

    // ==================== 检索与状态 ====================

    /** 检索测试：走统一检索管线（混合召回 → rerank → 阈值 → 文件名补全） */
    public KbRetrievalService.RetrievalResult search(Long kbId, String query, Integer topK, Double threshold) {
        AiKnowledgeBase kb = getById(kbId);
        int effectiveTopK = topK == null ? (kb.getTopK() == null ? 4 : kb.getTopK()) : topK;
        Double effectiveThreshold = threshold != null ? threshold : kb.getSimilarityThreshold();
        return retrievalService.retrieve(TenantContext.getRequiredTenantId(),
                List.of(kbId), query, effectiveTopK, effectiveThreshold);
    }

    /** 向量库状态（available + 全文检索支持 + 平台默认向量模型名，前端提示用） */
    public Map<String, Object> vectorStoreStatus() {
        try {
            KbVectorClient client = requireVectorClient();
            client.checkAvailable();
            Map<String, Object> status = new java.util.HashMap<>();
            status.put("available", true);
            status.put("fullText", client.fullTextSupported());
            vip.mseapp.ai.entity.AiModel defaultEmbedding = modelService.defaultEmbeddingModel();
            status.put("defaultEmbeddingModel", defaultEmbedding == null ? null : defaultEmbedding.getName());
            return status;
        } catch (ServiceException e) {
            return Map.of("available", false, "fullText", false, "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("available", false, "fullText", false,
                    "message", "向量库连接失败：" + e.getMessage());
        }
    }

    /** 检索增强可用性自检（智能体绑定知识库时前端提示用） */
    public boolean vectorStoreAvailable() {
        return vectorStoreStatus().get("available") == Boolean.TRUE;
    }

    public KbRetrievalService retrievalService() {
        return retrievalService;
    }

    public AiPlatformProperties properties() {
        return properties;
    }

    // ==================== 内部工具 ====================

    private void clearDocumentData(Long kbId, Long docId) {
        List<AiKbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<AiKbChunk>()
                .eq(AiKbChunk::getKbId, kbId).eq(AiKbChunk::getDocumentId, docId));
        if (!chunks.isEmpty()) {
            try {
                // 跨候选集合兜底清理：知识库改绑向量模型后旧向量仍在原模型集合
                requireVectorClient().deleteEverywhere(modelService.listEmbeddingCandidateIds(),
                        chunks.stream().map(c -> String.valueOf(c.getId())).toList());
            } catch (Exception e) {
                // 向量清理失败不阻断重建（旧向量仍带 docId 元数据，重切后按 id 删除兜底）
                log.warn("[mse-ai] 旧向量清理失败（继续重切）: kb={}, doc={}, {}", kbId, docId, e.getMessage());
            }
            chunks.forEach(c -> chunkMapper.deleteById(c.getId()));
        }
    }

    private String parseByTika(byte[] bytes, String fileName) {
        List<Document> parsed = new TikaDocumentReader(toResource(bytes, fileName)).get();
        StringBuilder text = new StringBuilder();
        for (Document document : parsed) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(document.getText());
        }
        return text.toString();
    }

    private Resource toResource(byte[] bytes, String fileName) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private static boolean isTextFile(String ext) {
        return ext != null && TEXT_EXTS.contains(ext.toLowerCase());
    }

    private AiKbDocument requireDocument(Long kbId, Long docId) {
        AiKbDocument document = documentMapper.selectById(docId);
        if (document == null || !document.getKbId().equals(kbId)) {
            throw new ServiceException(404, "文档不存在");
        }
        return document;
    }

    private int nextSeq(Long kbId, Long docId) {
        AiKbChunk last = chunkMapper.selectOne(new LambdaQueryWrapper<AiKbChunk>()
                .eq(AiKbChunk::getKbId, kbId).eq(AiKbChunk::getDocumentId, docId)
                .orderByDesc(AiKbChunk::getSeq).last("LIMIT 1"));
        return last == null ? 0 : last.getSeq() + 1;
    }

    private void refreshChunkCount(Long kbId, Long docId) {
        AiKbDocument document = documentMapper.selectById(docId);
        if (document != null) {
            Long count = chunkMapper.selectCount(new LambdaQueryWrapper<AiKbChunk>()
                    .eq(AiKbChunk::getKbId, kbId).eq(AiKbChunk::getDocumentId, docId));
            document.setChunkCount(count == null ? 0 : count.intValue());
            documentMapper.updateById(document);
        }
    }

    private KbVectorClient requireVectorClient() {
        return retrievalService.requireVectorClient();
    }
}
