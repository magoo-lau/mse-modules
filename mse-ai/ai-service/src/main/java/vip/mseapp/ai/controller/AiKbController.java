package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.ai.entity.AiKbChunk;
import vip.mseapp.ai.entity.AiKbDocument;
import vip.mseapp.ai.entity.AiKnowledgeBase;
import vip.mseapp.ai.kb.KbRetrievalService;
import vip.mseapp.ai.kb.KbService;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理（租户端）：库 / 文档（异步解析、重解析、重建索引）/ 分块管理 / 检索测试。
 */
@Validated
@RestController
@RequestMapping("/ai/kb")
public class AiKbController {

    private final KbService kbService;

    public AiKbController(KbService kbService) {
        this.kbService = kbService;
    }

    @SaCheckPermission(value = "ai:kb:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<AiKnowledgeBase>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                    @RequestParam(defaultValue = "10") long pageSize,
                                                    @RequestParam(required = false) String keyword) {
        Page<AiKnowledgeBase> page = kbService.page(pageNum, pageSize, keyword);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckPermission(value = "ai:kb:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}")
    public Result<AiKnowledgeBase> get(@PathVariable Long id) {
        return Result.ok(kbService.getById(id));
    }

    @SaCheckPermission(value = "ai:kb:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> save(@RequestBody AiKnowledgeBase form) {
        return Result.ok(kbService.save(form));
    }

    @SaCheckPermission(value = "ai:kb:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        kbService.delete(id);
        return Result.ok();
    }

    /** 向量库可用性 + 全文检索支持情况（前端上传入口禁用态与升级提示） */
    @SaCheckPermission(value = "ai:kb:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/vector-store-status")
    public Result<Map<String, Object>> vectorStoreStatus() {
        return Result.ok(kbService.vectorStoreStatus());
    }

    @SaCheckPermission(value = "ai:kb:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/documents")
    public Result<PageResult<AiKbDocument>> documents(@PathVariable Long id,
                                                      @RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "10") long pageSize) {
        Page<AiKbDocument> page = kbService.pageDocuments(id, pageNum, pageSize);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    /** 上传文档：落记录 + 归档后立即返回（异步解析，前端轮询状态） */
    @SaCheckPermission(value = "ai:kb:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/documents")
    public Result<AiKbDocument> upload(@PathVariable Long id, @RequestParam("file") MultipartFile file) throws Exception {
        return Result.ok(kbService.upload(id, file));
    }

    /** 下载归档原文件（归档存储可为本地目录或 mse-fs，行为一致） */
    @SaCheckPermission(value = "ai:kb:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/documents/{docId}/file")
    public void downloadOriginal(@PathVariable Long id, @PathVariable Long docId,
                                 jakarta.servlet.http.HttpServletResponse response) throws Exception {
        kbService.downloadOriginal(id, docId, response);
    }

    /** 重新解析：按当前知识库配置（切片方法/大小/重叠）全量重切重嵌入 */
    @SaCheckPermission(value = "ai:kb:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/documents/{docId}/reparse")
    public Result<Void> reparse(@PathVariable Long id, @PathVariable Long docId) {
        kbService.reparse(id, docId);
        return Result.ok();
    }

    /** 重建索引：知识库全部文档重新解析（修改切片配置后使存量生效） */
    @SaCheckPermission(value = "ai:kb:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/rebuild")
    public Result<Integer> rebuild(@PathVariable Long id) {
        return Result.ok(kbService.rebuild(id));
    }

    /** 重建索引（轻量）：不重解析文档，按现有切片正文跨集合清场后全量重嵌入（改绑向量模型后执行） */
    @SaCheckPermission(value = "ai:kb:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/reindex")
    public Result<Void> reindex(@PathVariable Long id) {
        kbService.reindex(id);
        return Result.ok();
    }

    @SaCheckPermission(value = "ai:kb:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}/documents/{docId}")
    public Result<Void> deleteDocument(@PathVariable Long id, @PathVariable Long docId) {
        kbService.deleteDocument(id, docId);
        return Result.ok();
    }

    // ==================== 分块管理（人工干预） ====================

    @SaCheckPermission(value = "ai:kb:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/documents/{docId}/chunks")
    public Result<PageResult<AiKbChunk>> chunks(@PathVariable Long id, @PathVariable Long docId,
                                                @RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        Page<AiKbChunk> page = kbService.pageChunks(id, docId, pageNum, pageSize);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckPermission(value = "ai:kb:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/documents/{docId}/chunks")
    public Result<AiKbChunk> addChunk(@PathVariable Long id, @PathVariable Long docId,
                                      @RequestBody @Validated ChunkRequest request) {
        return Result.ok(kbService.addChunk(id, docId, request.content()));
    }

    @SaCheckPermission(value = "ai:kb:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping("/{id}/chunks/{chunkId}")
    public Result<Void> updateChunk(@PathVariable Long id, @PathVariable Long chunkId,
                                    @RequestBody @Validated ChunkRequest request) {
        kbService.updateChunk(id, chunkId, request.content());
        return Result.ok();
    }

    @SaCheckPermission(value = "ai:kb:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}/chunks/{chunkId}")
    public Result<Void> deleteChunk(@PathVariable Long id, @PathVariable Long chunkId) {
        kbService.deleteChunk(id, chunkId);
        return Result.ok();
    }

    /** 检索测试：混合召回 + 重排 + 阈值，返回命中切片（含文件名与通道明细） */
    @SaCheckPermission(value = "ai:kb:search", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/search")
    public Result<KbRetrievalService.RetrievalResult> search(@RequestBody @Validated KbSearchRequest request) {
        return Result.ok(kbService.search(request.kbId(), request.query(), request.topK(), request.threshold()));
    }

    public record KbSearchRequest(Long kbId, @NotBlank(message = "查询不能为空") String query,
                                  Integer topK, Double threshold) {
    }

    public record ChunkRequest(@NotBlank(message = "切片内容不能为空") String content) {
    }
}
