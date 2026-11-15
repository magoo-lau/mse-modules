package vip.mseapp.fs.dto;

import java.util.List;

/**
 * 分片组装完成请求（POST /fs/file/multipart/{id}/complete JSON body）。
 */
public class MultipartCompleteRequest {

    /** 已成功上传的分片（partNumber + 响应 ETag，原样回传） */
    private List<Part> parts;

    public List<Part> getParts() {
        return parts;
    }

    public void setParts(List<Part> parts) {
        this.parts = parts;
    }

    /** 单个分片的回执 */
    public record Part(int partNumber, String etag) {
    }
}
