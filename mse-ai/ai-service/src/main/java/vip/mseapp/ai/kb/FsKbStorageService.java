package vip.mseapp.ai.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.fs.api.RemoteFileService;
import vip.mseapp.fs.api.dto.FileMetaRequest;
import vip.mseapp.fs.api.dto.FileMetaResult;
import vip.mseapp.fs.api.dto.FileUploadRequest;

import java.util.Base64;

/**
 * 知识库原文件 mse-fs 归档实现（FsAvailable 时优先启用）：内容统一存 mse-fs
 * （fs_file 元数据 + local/rustfs 物理双后端，bizType=ai-kb，bizId=文档 id），
 * 归档串 {@code fs:{fileId}} 记入 ai_kb_document.storage_path——表结构零变更。
 *
 * <p>读取/删除按归档串分流：fs 串经 RPC {@link RemoteFileService}，历史本地路径
 * 委托 {@link LocalKbStorageService}（切到 mse-fs 后旧文档仍可重新解析）。
 * 微服务形态经 dubbo 远调 mse-fs，单体聚合 fs-service 时本地直调。
 */
@Slf4j
public class FsKbStorageService implements KbStorageService {

    /** fs 归档串前缀（storage_path 以此区分 mse-fs 与历史本地路径） */
    public static final String FS_PREFIX = "fs:";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** RPC 引用（60s：文档归档走 base64，大文档链路留足余量） */
    @RpcReference(dubbo = @RpcReference.Dubbo(timeout = 60_000))
    private RemoteFileService remoteFileService;

    private final LocalKbStorageService local;

    public FsKbStorageService(LocalKbStorageService local) {
        this.local = local;
    }

    @Override
    public String archive(Long docId, String fileName, MultipartFile file) {
        FileUploadRequest request = new FileUploadRequest();
        request.setFileName(fileName);
        request.setContentType(file.getContentType());
        try {
            request.setContentBase64(Base64.getEncoder().encodeToString(file.getBytes()));
        } catch (Exception e) {
            throw new ServiceException(500, "文件归档读取失败：" + e.getMessage());
        }
        request.setBizType("ai-kb");
        request.setBizId(String.valueOf(docId));
        try {
            FileMetaResult meta = JSON.readValue(
                    remoteFileService.uploadFile(JSON.writeValueAsString(request)), FileMetaResult.class);
            return FS_PREFIX + meta.getFileId();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(500, "文件归档失败：" + e.getMessage());
        }
    }

    @Override
    public byte[] read(String path) {
        if (path != null && path.startsWith(FS_PREFIX)) {
            return downloadFs(Long.parseLong(path.substring(FS_PREFIX.length())));
        }
        // 历史本地归档：切到 mse-fs 后仍可读
        return local.read(path);
    }

    @Override
    public void remove(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (!path.startsWith(FS_PREFIX)) {
            local.remove(path);
            return;
        }
        try {
            FileMetaRequest request = new FileMetaRequest();
            request.setFileId(Long.parseLong(path.substring(FS_PREFIX.length())));
            remoteFileService.deleteFile(JSON.writeValueAsString(request));
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // 归档清理失败不阻断业务，mse-fs 侧留孤儿对象可由存储侧清理
            log.warn("[mse-ai] mse-fs 归档删除失败（path={}）：{}", path, e.getMessage());
        }
    }

    private byte[] downloadFs(Long fileId) {
        try {
            FileMetaRequest request = new FileMetaRequest();
            request.setFileId(fileId);
            String json = remoteFileService.downloadFile(JSON.writeValueAsString(request));
            return Base64.getDecoder().decode(JSON.readValue(json, vip.mseapp.fs.api.dto.FileDownloadResult.class)
                    .getContentBase64());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "归档文件读取失败：" + e.getMessage());
        }
    }
}
