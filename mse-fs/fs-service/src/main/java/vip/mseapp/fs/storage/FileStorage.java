package vip.mseapp.fs.storage;

import vip.mseapp.core.exception.ServiceException;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * 文件存储抽象：物理对象（本地目录 / RustFS S3 兼容对象）的读写删统一入口。
 *
 * <p>两个实现按 {@code mse.fs.storage.type} 互斥装配——local（缺省）/ rustfs；
 * 元数据（fs_file 行）与物理对象分离，本接口只管物理对象。
 *
 * <p>存储键即租户隔离边界：首段恒为租户 ID（{@link #buildKey}），任何实现不得生成
 * 跨租户前缀的键，预签名直链也按单键签发，泄露面被限制在单个文件。
 */
public interface FileStorage {

    /** 存储类型标识（fs_file.storage_type 取值）：本地文件系统 */
    String TYPE_LOCAL = "local";
    /** 存储类型标识：RustFS（S3 兼容） */
    String TYPE_RUSTFS = "rustfs";

    /** 分片直传最大分片数（S3 协议上限 10000，取保守值控制预签名 URL 批量大小） */
    int MAX_MULTIPART_PARTS = 1000;
    /** 分片直传最小分片大小（8MB；大文件自动放大分片以不超过上限） */
    long MIN_MULTIPART_PART_SIZE = 8L * 1024 * 1024;

    /** 按内容大小计算分片大小：末片允许不足，分片数不超过 MAX_MULTIPART_PARTS */
    static long resolvePartSize(long totalSize) {
        long partSize = MIN_MULTIPART_PART_SIZE;
        while (totalSize / partSize > MAX_MULTIPART_PARTS) {
            partSize *= 2;
        }
        return partSize;
    }

    /**
     * 存储一个物理对象。
     *
     * @param tenantId    归属租户（键首段，取自租户上下文）
     * @param fileId      文件元数据 ID（预生成雪花 ID，键内保证唯一）
     * @param ext         扩展名（小写、不含点，可为空串）
     * @param contentType 内容 MIME 类型（可空）
     * @param in          内容输入流（本方法负责关闭）
     * @param size        内容字节数（S3 流式上传必须）
     * @return 存储键（落库 fs_file.storage_key）
     */
    String store(Long tenantId, Long fileId, String ext, String contentType, InputStream in, long size);

    /** 打开对象输入流（调用方负责关闭；对象不存在时抛 ServiceException） */
    InputStream open(String storageKey);

    /** 删除对象（幂等：对象不存在不报错；失败抛 ServiceException，元数据侧应尽力而为） */
    void delete(String storageKey);

    /**
     * 按指定存储键写入对象（存储迁移用：目标实现沿用源元数据的键，搬移后无需改元数据行）。
     * 默认实现不可用时抛 ServiceException。
     */
    default String storeAs(String storageKey, String contentType, InputStream in, long size) {
        throw new ServiceException(400, "当前存储实现不支持按指定键写入");
    }

    /**
     * 发起分片直传会话并签发全部分片的上传 URL（仅对象存储实现支持；本地实现抛 ServiceException）。
     * 分片大小按 max(8MB, 向上取整使分片数不超过 {@link #MAX_MULTIPART_PARTS}) 计算。
     */
    default MultipartSession initMultipart(Long tenantId, Long fileId, String ext, String contentType,
                                           long size, Duration partUrlTtl) {
        throw new ServiceException(400, "当前存储模式不支持分片直传（仅 rustfs 对象存储可用）");
    }

    /** 组装分片对象（partNumber→ETag，凭 init 返回的 uploadId；成功后对象按整键可见） */
    default void completeMultipart(String storageKey, String uploadId, Map<Integer, String> partEtags) {
        throw new ServiceException(400, "当前存储模式不支持分片直传（仅 rustfs 对象存储可用）");
    }

    /** 放弃分片会话（幂等：会话不存在不报错），存储侧清理已传分片 */
    default void abortMultipart(String storageKey, String uploadId) {
        throw new ServiceException(400, "当前存储模式不支持分片直传（仅 rustfs 对象存储可用）");
    }

    /**
     * 签发预签名直链（仅对象存储实现支持；本地实现抛 ServiceException）。
     */
    default String presign(String storageKey, Duration ttl) {
        throw new ServiceException(400, "当前存储模式不支持预签名直链");
    }

    /** 存储类型标识（落库 fs_file.storage_type） */
    String type();

    /**
     * 构建存储键：{tenantId}/{yyyyMM}/{fileId}.{ext}。
     * 首段租户 ID 即物理层隔离边界；月份分段便于对象浏览与生命周期管理。
     */
    static String buildKey(Long tenantId, Long fileId, String ext) {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        return tenantId + "/" + month + "/" + fileId + (ext == null || ext.isEmpty() ? "" : "." + ext);
    }

    /** 提取并净化扩展名：小写字母数字、长度 1-16，不合法或缺失返回空串（防路径拼接与键注入） */
    static String sanitizeExt(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,16}") ? ext : "";
    }
}
