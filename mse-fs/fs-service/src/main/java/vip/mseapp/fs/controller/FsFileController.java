package vip.mseapp.fs.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.fs.dto.MultipartCompleteRequest;
import vip.mseapp.fs.dto.MultipartInitRequest;
import vip.mseapp.fs.dto.MultipartInitResult;
import vip.mseapp.fs.entity.FsFile;
import vip.mseapp.fs.service.FsFileService;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件管理（租户端）：上传 / 检索 / 代理下载 / 缩略图 / 预签名直链 / 分片直传 / 删除。
 */
@RestController
@RequestMapping("/fs/file")
public class FsFileController {

    private final FsFileService fileService;

    public FsFileController(FsFileService fileService) {
        this.fileService = fileService;
    }

    @SaCheckPermission(value = "fs:file:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/upload")
    public Result<FsFile> upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam(value = "bizType", required = false) String bizType,
                                 @RequestParam(value = "bizId", required = false) String bizId) {
        return Result.ok(fileService.upload(file, bizType, bizId));
    }

    @SaCheckPermission(value = "fs:file:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/multipart/init")
    public Result<MultipartInitResult> initMultipart(@RequestBody MultipartInitRequest request) {
        return Result.ok(fileService.initMultipart(request.getFileName(), request.getContentType(),
                request.getSize(), request.getBizType(), request.getBizId()));
    }

    @SaCheckPermission(value = "fs:file:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/multipart/{id}/complete")
    public Result<FsFile> completeMultipart(@PathVariable Long id, @RequestBody MultipartCompleteRequest request) {
        Map<Integer, String> etags = new LinkedHashMap<>();
        if (request.getParts() != null) {
            for (MultipartCompleteRequest.Part part : request.getParts()) {
                etags.put(part.partNumber(), part.etag());
            }
        }
        return Result.ok(fileService.completeMultipart(id, etags));
    }

    @SaCheckPermission(value = "fs:file:upload", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/multipart/{id}/abort")
    public Result<Void> abortMultipart(@PathVariable Long id) {
        fileService.abortMultipart(id);
        return Result.ok();
    }

    @SaCheckPermission(value = "fs:file:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<FsFile>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String bizType,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return Result.ok(fileService.page(pageNum, pageSize, keyword, bizType, beginTime, endTime));
    }

    @SaCheckPermission(value = "fs:file:download", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        fileService.download(id, response);
    }

    @SaCheckPermission(value = "fs:file:download", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/thumbnail")
    public void thumbnail(@PathVariable Long id,
                          @RequestParam(value = "width", required = false) Integer width,
                          HttpServletResponse response) throws IOException {
        fileService.downloadThumbnail(id, width, response);
    }

    @SaCheckPermission(value = "fs:file:download", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/presign-url")
    public Result<String> presignUrl(@PathVariable Long id) {
        return Result.ok(fileService.presignUrl(id));
    }

    @SaCheckPermission(value = "fs:file:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return Result.ok();
    }

    @SaCheckPermission(value = "fs:file:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/storage-info")
    public Result<Map<String, Object>> storageInfo() {
        return Result.ok(fileService.storageInfo());
    }
}
