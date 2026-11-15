package vip.mseapp.ai.avatar;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.fs.api.FsAssetUrls;
import vip.mseapp.fs.api.FileAccessLevel;
import vip.mseapp.fs.api.RemoteFileService;
import vip.mseapp.fs.api.dto.FileMetaResult;
import vip.mseapp.fs.api.dto.FileUploadRequest;

import java.io.IOException;
import java.util.Base64;

/**
 * 智能体头像 mse-fs 公开素材通道存储（mse-fs 可用时优先启用）。
 *
 * <p>上传走 {@link RemoteFileService#uploadFile}（accessLevel=public，bizType=agent-avatar），
 * 返回 {@code /fs/asset/{tenantId}/{fileId}.{后缀}}（FsAssetUrls 规范拼法）——mse-fs 的免登录素材端点
 * 按租户段路由落库寻址，fileId 内容不变可永久强缓存；IM 机器人头像带出该地址亦可被聊天客户端寻址。
 * 微服务形态经 dubbo 远调 mse-fs，单体聚合 fs-service 时本地直调（mse-fs 未引入时本类不会被装配）。
 */
public class FsAgentAvatarStorage implements AgentAvatarStorage {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** RPC 引用（60s：头像 ≤2MB base64 约 2.7MB，弱链路上传留足余量） */
    @RpcReference(dubbo = @RpcReference.Dubbo(timeout = 60_000))
    private RemoteFileService remoteFileService;

    @Override
    public String upload(MultipartFile file) {
        String ext = AgentAvatarRules.validate(file);
        FileUploadRequest request = new FileUploadRequest();
        request.setFileName(file.getOriginalFilename());
        // 按扩展名定型 Content-Type，与本地实现同口径（不信任上传方声明）
        request.setContentType(AgentAvatarRules.CONTENT_TYPES.get(ext));
        try {
            request.setContentBase64(Base64.getEncoder().encodeToString(file.getBytes()));
        } catch (IOException e) {
            throw new ServiceException("头像读取失败：" + e.getMessage());
        }
        request.setBizType("agent-avatar");
        request.setAccessLevel(FileAccessLevel.PUBLIC);
        FileMetaResult meta;
        try {
            meta = JSON.readValue(remoteFileService.uploadFile(JSON.writeValueAsString(request)),
                    FileMetaResult.class);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("头像上传失败：" + e.getMessage());
        }
        return FsAssetUrls.publicAssetUrl(TenantContext.getRequiredTenantId(), meta.getFileId(), meta.getFileName());
    }

    @Override
    public void write(String name, HttpServletResponse response) throws IOException {
        // 本类返回的 /fs/asset/{tenantId}/{id}.{ext} 由 mse-fs 免登录素材端点输出，不经本地端点
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
}
