package vip.mseapp.ai.kb.chunk;

import org.springframework.core.io.Resource;
import vip.mseapp.core.exception.ServiceException;

import java.util.List;

/**
 * 整篇切片器：整篇文档一个切片（短文档、全文检索场景）。
 *
 * <p>超长文本在 24000 字符处截断——嵌入接口普遍有数千 token 上限，
 * 截断只影响超长尾部（ONE 方法本就面向短文档）。
 */
public class OneTextChunker implements TextChunker {

    private static final int MAX_CHARS = 24_000;

    @Override
    public ChunkMethod method() {
        return ChunkMethod.ONE;
    }

    @Override
    public List<String> split(String text, Resource resource, ChunkParams params) {
        if (text == null || text.isBlank()) {
            throw new ServiceException(400, "文档未解析出文本内容");
        }
        String content = text.strip();
        if (content.length() > MAX_CHARS) {
            content = content.substring(0, MAX_CHARS);
        }
        return List.of(content);
    }
}
