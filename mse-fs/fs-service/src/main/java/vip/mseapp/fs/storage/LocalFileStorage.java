package vip.mseapp.fs.storage;

import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.fs.config.FsStorageProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件系统存储（缺省实现：未配置 rustfs 时即本实现）。
 *
 * <p>物理路径 {baseDir}/{tenantId}/{yyyyMM}/{fileId}.{ext}——首段租户目录即物理层隔离边界；
 * 生产部署建议挂载持久卷。分片直传/预签名直链为对象存储专属能力，本实现一律拒绝。
 */
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(FsStorageProperties properties) {
        this.baseDir = Path.of(properties.getLocal().getBaseDir()).toAbsolutePath().normalize();
    }

    @Override
    public String store(Long tenantId, Long fileId, String ext, String contentType, InputStream in, long size) {
        return storeAs(FileStorage.buildKey(tenantId, fileId, ext), contentType, in, size);
    }

    @Override
    public String storeAs(String storageKey, String contentType, InputStream in, long size) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ServiceException("文件保存失败：" + e.getMessage());
        }
        return storageKey;
    }

    @Override
    public InputStream open(String storageKey) {
        Path target = resolve(storageKey);
        if (!Files.isRegularFile(target)) {
            throw new ServiceException(404, "文件对象不存在");
        }
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new ServiceException("文件读取失败：" + e.getMessage());
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new ServiceException("文件删除失败：" + e.getMessage());
        }
    }

    @Override
    public String type() {
        return TYPE_LOCAL;
    }

    /** 存储键解析到物理路径（normalize 后必须仍落在根目录内，防键注入穿越） */
    private Path resolve(String storageKey) {
        Path target = baseDir.resolve(storageKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new ServiceException(400, "非法存储键：" + storageKey);
        }
        return target;
    }
}
