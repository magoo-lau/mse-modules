package vip.mseapp.fs.storage;

import java.util.List;

/**
 * 分片直传会话（仅对象存储实现支持）：init 时签发全部分片的预签名上传 URL，
 * 客户端逐片 PUT 后凭响应 ETag 回调 complete 组装对象。
 *
 * @param storageKey 存储键（init 时即落库 fs_file.storage_key）
 * @param uploadId   存储侧分片会话 ID（落库 fs_file.upload_id，complete/abort 凭它寻址）
 * @param partSize   分片大小（字节，末片可小于该值）
 * @param parts      预签名分片（partNumber 从 1 起，url 为免登录直传地址，限时有效）
 */
public record MultipartSession(String storageKey, String uploadId, long partSize, List<PresignedPart> parts) {

    /** 单个分片的预签名直传地址 */
    public record PresignedPart(int partNumber, String url) {
    }
}
