package vip.mseapp.ai.kb.chunk;

import org.springframework.core.io.Resource;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用切片器：分隔符感知的句子聚合 + 相邻切片重叠。
 *
 * <p>先按句末标点（。！？!?；;）与换行切出最小语义单元，再按目标 token 数
 * 聚合成片；收片时携带尾部不超过 chunkOverlap 的句子进入下一片，避免语义被截断。
 * 单句超过 chunkSize 时独立成片（不再硬切，保留完整语义交给嵌入模型截断）。
 */
public class GeneralTextChunker implements TextChunker {

    @Override
    public ChunkMethod method() {
        return ChunkMethod.GENERAL;
    }

    @Override
    public List<String> split(String text, Resource resource, ChunkParams params) {
        List<String> sentences = toSentences(text);
        if (sentences.isEmpty()) {
            throw new ServiceException(400, "文档未解析出文本内容");
        }
        int chunkSize = params.chunkSize();
        int overlap = params.chunkOverlap();

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < sentences.size()) {
            StringBuilder buf = new StringBuilder();
            int acc = 0;
            int end = start;
            while (end < sentences.size() && (acc == 0 || acc + ChunkParams.estimateTokens(sentences.get(end)) <= chunkSize)) {
                String sentence = sentences.get(end);
                buf.append(sentence);
                acc += ChunkParams.estimateTokens(sentence);
                end++;
            }
            String chunk = buf.toString().trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= sentences.size()) {
                break;
            }
            // 重叠：从已消费窗口尾部回退，携带不超过 overlap 的句子进入下一片
            int carry = 0;
            int carryStart = end;
            while (carryStart - 1 > start
                    && carry + ChunkParams.estimateTokens(sentences.get(carryStart - 1)) <= overlap) {
                carryStart--;
                carry += ChunkParams.estimateTokens(sentences.get(carryStart));
            }
            start = carryStart;
        }
        return chunks;
    }

    /** 切出最小语义单元：句末标点后断句，换行处断句；剔除空白片段 */
    static List<String> toSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String piece : normalized.split("(?<=[。！？!?；;])|\n")) {
            String trimmed = piece.strip();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }
}
