package vip.mseapp.ai.avatar;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.core.exception.ServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 智能体头像本地磁盘存储（mse-fs 未引入/未部署时的兜底实现）：目录 {@code mse.ai.avatar.storage-path}，
 * 落盘名 {@code {雪花id}.{ext}}，对外暴露站内地址 {@code /ai/agent/asset/{name}}
 * （免登录：img 标签不携带 token，微服务形态需加入网关 mse.gateway.ignore-urls）。
 * 头像无 DB 行：更换头像后旧文件残留磁盘（体量小，不做 GC）。
 */
public class LocalAgentAvatarStorage implements AgentAvatarStorage {

    private final String avatarDir;

    public LocalAgentAvatarStorage(String avatarDir) {
        this.avatarDir = avatarDir;
    }

    @Override
    public String upload(MultipartFile file) {
        String ext = AgentAvatarRules.validate(file);
        String name = IdWorker.getId() + "." + ext;
        Path target = Path.of(avatarDir, name);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ServiceException("头像保存失败：" + e.getMessage());
        }
        return "/ai/agent/asset/" + name;
    }

    @Override
    public void write(String name, HttpServletResponse response) throws IOException {
        if (name == null || !AgentAvatarRules.ASSET_NAME_PATTERN.matcher(name).matches()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Path target = Path.of(avatarDir, name);
        if (!Files.isRegularFile(target)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String ext = AgentAvatarRules.extension(name);
        response.setContentType(AgentAvatarRules.CONTENT_TYPES.get(ext));
        response.setHeader("Content-Disposition", "inline");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'");
        response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        response.setContentLengthLong(Files.size(target));
        try (InputStream in = Files.newInputStream(target); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }
}
