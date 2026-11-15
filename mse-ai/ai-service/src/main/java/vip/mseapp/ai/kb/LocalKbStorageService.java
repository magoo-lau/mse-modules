package vip.mseapp.ai.kb;

import vip.mseapp.core.exception.ServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 知识库原文件本地目录归档（mse-fs 未引入 / 未部署时的兜底实现）：
 * 目录 {@code mse.ai.kb.storage-path}（默认 {user.home}/.mse/ai-kb），落盘名 {@code {docId}{ext}}。
 */
public class LocalKbStorageService implements KbStorageService {

    private final Path root;

    public LocalKbStorageService(String rootPath) {
        this.root = Path.of(rootPath);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ServiceException(500, "知识库归档目录创建失败：" + rootPath + "（" + e.getMessage() + "）");
        }
    }

    @Override
    public String archive(Long docId, String fileName, org.springframework.web.multipart.MultipartFile file) {
        String ext = KbStorageService.extOf(fileName);
        Path target = root.resolve(docId + ext);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ServiceException(500, "文件归档失败：" + e.getMessage());
        }
        return target.toString();
    }

    @Override
    public byte[] read(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new ServiceException(400, "归档文件读取失败（可能已被清理）：" + path);
        }
    }

    @Override
    public void remove(String path) {
        if (path == null || path.isBlank() || path.startsWith(FsKbStorageService.FS_PREFIX)) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            // 归档清理失败不阻断业务，仅留孤儿文件
        }
    }
}
