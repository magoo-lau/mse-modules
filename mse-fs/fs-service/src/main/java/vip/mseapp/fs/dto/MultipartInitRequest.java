package vip.mseapp.fs.dto;

/**
 * 分片直传发起请求（POST /fs/file/multipart/init JSON body）。
 */
public class MultipartInitRequest {

    /** 原始文件名（扩展名参与存储键生成） */
    private String fileName;

    /** 内容 MIME 类型 */
    private String contentType;

    /** 文件总字节数（用于计算分片大小与片数） */
    private long size;

    /** 业务分类（可选） */
    private String bizType;

    /** 业务关联 ID（可选） */
    private String bizId;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }
}
