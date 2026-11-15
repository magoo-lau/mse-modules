package vip.mseapp.fs.rpc;

import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.fs.api.RemoteFileService;
import vip.mseapp.fs.api.dto.FileDownloadResult;
import vip.mseapp.fs.api.dto.FileMetaRequest;
import vip.mseapp.fs.api.dto.FileMetaResult;
import vip.mseapp.fs.api.dto.FileOperationResult;
import vip.mseapp.fs.api.dto.FileUploadRequest;
import vip.mseapp.fs.entity.FsFile;
import vip.mseapp.fs.service.FsFileService;

import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * 文件存储开放契约实现（复用 FsFileService，机器语义 JSON 进出）。
 * 装配：由 FsServiceAutoConfiguration @Bean 注册（可声明同契约 Bean 整体替换）。
 */
@RpcService(RemoteFileService.class)
public class RemoteFileServiceImpl implements RemoteFileService {

    private final FsFileService fileService;
    private final JsonMapper objectMapper;

    public RemoteFileServiceImpl(FsFileService fileService, JsonMapper objectMapper) {
        this.fileService = fileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String uploadFile(String requestJson) {
        FileUploadRequest request = parse(requestJson, FileUploadRequest.class);
        if (request.getFileName() == null || request.getFileName().isBlank()
                || request.getContentBase64() == null || request.getContentBase64().isBlank()) {
            throw new ServiceException(400, "fileName 与 contentBase64 不能为空");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(request.getContentBase64());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(400, "contentBase64 非法：" + e.getMessage());
        }
        FsFile meta = fileService.upload(request.getFileName(), request.getContentType(),
                new ByteArrayInputStream(content), content.length, request.getBizType(), request.getBizId(),
                request.getAccessLevel());
        return toJson(toMetaResult(meta));
    }

    @Override
    public String getFileMeta(String requestJson) {
        FileMetaRequest request = parse(requestJson, FileMetaRequest.class);
        requireFileId(request);
        return toJson(toMetaResult(fileService.get(request.getFileId())));
    }

    @Override
    public String downloadFile(String requestJson) {
        FileMetaRequest request = parse(requestJson, FileMetaRequest.class);
        requireFileId(request);
        FsFile meta = fileService.get(request.getFileId());
        FileDownloadResult result = new FileDownloadResult();
        result.setFileId(meta.getId());
        result.setFileName(meta.getFileName());
        result.setContentType(meta.getContentType());
        result.setFileSize(meta.getFileSize());
        result.setContentBase64(Base64.getEncoder().encodeToString(fileService.readBytes(request.getFileId())));
        return toJson(result);
    }

    @Override
    public String deleteFile(String requestJson) {
        FileMetaRequest request = parse(requestJson, FileMetaRequest.class);
        requireFileId(request);
        fileService.delete(request.getFileId());
        FileOperationResult result = new FileOperationResult();
        result.setFileId(request.getFileId());
        result.setDeleted(true);
        return toJson(result);
    }

    private FileMetaResult toMetaResult(FsFile meta) {
        FileMetaResult result = new FileMetaResult();
        result.setFileId(meta.getId());
        result.setFileName(meta.getFileName());
        result.setContentType(meta.getContentType());
        result.setFileSize(meta.getFileSize());
        result.setStorageType(meta.getStorageType());
        result.setBizType(meta.getBizType());
        result.setBizId(meta.getBizId());
        result.setAccessLevel(meta.getAccessLevel());
        result.setContentMd5(meta.getContentMd5());
        result.setCreateTime(meta.getCreateTime());
        return result;
    }

    private void requireFileId(FileMetaRequest request) {
        if (request.getFileId() == null) {
            throw new ServiceException(400, "fileId 不能为空");
        }
    }

    private <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new ServiceException(400, "请求 JSON 不能为空");
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new ServiceException(400, "请求 JSON 解析失败: " + e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException(500, "响应序列化失败: " + e.getMessage());
        }
    }
}
