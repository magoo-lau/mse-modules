package vip.mseapp.ai.kb;

import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库原文件归档契约：上传即归档（返回归档寻址串，记录到 ai_kb_document.storage_path），
 * 支撑失败重试、重新解析/重建索引与原文回下载。
 *
 * <p>实现二选一，由装配按 mse-fs 可用性自动选择（{@code mse.ai.kb.storage-mode} 可显式覆盖）：
 * <ul>
 *   <li>{@link FsKbStorageService}：统一存 mse-fs（bizType=ai-kb），归档串 {@code fs:{fileId}}；
 *       mse-fs 未部署/未引入时自动让位；</li>
 *   <li>{@link LocalKbStorageService}：单机目录兜底（目录 mse.ai.kb.storage-path）。</li>
 * </ul>
 * 声明同类型 Bean 即可整体替换（{@code @ConditionalOnMissingBean(KbStorageService.class)}）。
 */
public interface KbStorageService {

    /** 归档上传文件，返回归档寻址串（记录到 ai_kb_document.storage_path） */
    String archive(Long docId, String fileName, MultipartFile file);

    /** 读取归档内容（重新解析/重建索引/原文下载用） */
    byte[] read(String path);

    /** 删除归档（失败不阻断业务） */
    void remove(String path);

    /** 文件名 → 小写扩展名（不含点，无扩展名返回空串） */
    static String extOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }
}
