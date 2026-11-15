package vip.mseapp.im.service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * IM 用户头像存储契约（聊天客户端自设头像，{@code mse.im.avatar-storage} 可显式覆盖 auto/fs/local）。
 *
 * <p>默认实现二选一，由装配按 mse-fs 可用性自动选择：
 * <ul>
 *   <li>{@link FsImAvatarStorage}：统一存 mse-fs 公开素材通道（access_level=public），
 *       返回 {@code /fs/asset/{tenantId}/{fileId}.{后缀}}；mse-fs 未部署/未引入时自动让位；</li>
 *   <li>{@link LocalImAvatarStorage}：本地磁盘兜底（不依赖 mse-fs 部署也能跑），
 *       经 /im/client/asset/{name} 免登录读取（微服务形态需加入网关 mse.gateway.ignore-urls）。</li>
 * </ul>
 * 声明同类型 Bean 即可整体替换（如 OSS/MinIO，{@code @ConditionalOnMissingBean(ImAvatarStorage.class)}）。
 */
public interface ImAvatarStorage {

    /**
     * 保存上传的头像，返回可直接写入 im_user_profile.face_url 的站内地址（img 标签免登录可加载）。
     */
    String upload(MultipartFile file);

    /** 按文件名输出头像（inline + 永久强缓存 + nosniff；CSP 禁脚本） */
    void write(String name, HttpServletResponse response) throws IOException;
}
