package vip.mseapp.fs.openapi;

import vip.mseapp.fs.api.RemoteFileService;
import vip.mseapp.open.api.OpenApiContributor;
import vip.mseapp.open.api.OpenApiDescriptor;

import java.util.List;

/**
 * 文件存储开放能力声明（开放平台自注册：应用就绪后由 open-api 推送器推送至 mse-open 目录）。
 *
 * <p>复用既有契约 {@link RemoteFileService}——内容经 base64 携带，适合小文件（建议 &lt; 5MB），
 * 大文件走 mse-fs 的 HTTP 端点。上传默认租户私有；开放场景如需公开素材，
 * 请求体带 accessLevel=public（可经 /fs/asset/{tenantId}/{fileId}.{后缀} 匿名读）。
 */
public class FsOpenApiContributor implements OpenApiContributor {

    public static final String FILE_CONTRACT = "vip.mseapp.fs.api.RemoteFileService";

    @Override
    public List<OpenApiDescriptor> descriptors() {
        return List.of(
                descriptor("open:fs:file-upload", "上传文件", "POST", "/open/api/fs/file/upload",
                        FILE_CONTRACT + "#uploadFile",
                        "body: fileName/contentType/contentBase64(必填)、bizType/bizId/accessLevel(可选)；"
                                + "返回文件元数据（fileId 等），同租户同 MD5 秒传复用对象"),
                descriptor("open:fs:file-get", "查询文件元数据", "GET", "/open/api/fs/file/{fileId}",
                        FILE_CONTRACT + "#getFileMeta",
                        "返回文件元数据（跨租户 fileId 不可见，404 语义）"),
                descriptor("open:fs:file-download", "下载文件内容", "POST", "/open/api/fs/file/{fileId}/download",
                        FILE_CONTRACT + "#downloadFile",
                        "返回 contentBase64（小文件场景；大文件走服务代理下载端点）"),
                descriptor("open:fs:file-delete", "删除文件", "DELETE", "/open/api/fs/file/{fileId}",
                        FILE_CONTRACT + "#deleteFile",
                        "逻辑删元数据并尽力删除物理对象（秒传共享对象仍有引用时保留）"));
    }

    private static OpenApiDescriptor descriptor(String code, String name, String method, String path,
                                                String target, String description) {
        OpenApiDescriptor item = new OpenApiDescriptor();
        item.setCode(code);
        item.setName(name);
        item.setHttpMethod(method);
        item.setPath(path);
        item.setTarget(target);
        item.setDescription(description);
        return item;
    }
}
