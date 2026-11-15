package vip.mseapp.ai.kb.chunk;

import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 切片器策略接口：一种 {@link ChunkMethod} 对应一个实现，无状态。
 *
 * @see GeneralTextChunker
 * @see QaTextChunker
 * @see TableTextChunker
 * @see BookTextChunker
 * @see OneTextChunker
 */
public interface TextChunker {

    /** 本切片器对应的切片方法 */
    ChunkMethod method();

    /**
     * 切分文档为切片文本列表。
     *
     * @param text     主文本（Tika 解析结果或 UTF-8 直读文本；结构信息已拍平）
     * @param resource 原始文件（qa/table 方法解析 Excel/CSV 二进制用，可为 null）
     * @param params   切片参数（chunkSize/chunkOverlap）
     * @return 切片文本列表（保序，非空）
     */
    List<String> split(String text, Resource resource, ChunkParams params);
}
