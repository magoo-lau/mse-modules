package vip.mseapp.fs.dto;

import java.util.List;

/**
 * 分片直传发起结果（服务端已登记元数据并签发全部分片 URL；客户端逐片 PUT 后回调 complete）。
 *
 * @param fileId   文件元数据 ID（complete/abort 凭它寻址）
 * @param partSize 分片大小（字节；末片可小于该值）
 * @param parts    预签名分片清单（partNumber 从 1 起，url 限时直传地址）
 */
public record MultipartInitResult(Long fileId, long partSize, List<PartUrl> parts) {

    /** 单个分片的直传地址 */
    public record PartUrl(int partNumber, String url) {
    }
}
