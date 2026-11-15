package vip.mseapp.ai.kb.chunk;

import org.springframework.core.io.Resource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 切片器分发：按 {@link ChunkMethod} 路由到对应实现（无状态单例，静态装配）。
 */
public final class TextChunkers {

    private static final Map<ChunkMethod, TextChunker> CHUNKERS = new EnumMap<>(ChunkMethod.class);

    static {
        register(new GeneralTextChunker());
        register(new QaTextChunker());
        register(new TableTextChunker());
        register(new BookTextChunker());
        register(new OneTextChunker());
    }

    private TextChunkers() {
    }

    private static void register(TextChunker chunker) {
        CHUNKERS.put(chunker.method(), chunker);
    }

    public static TextChunker of(ChunkMethod method) {
        TextChunker chunker = CHUNKERS.get(method);
        if (chunker == null) {
            throw new IllegalArgumentException("不支持的切片方法: " + method);
        }
        return chunker;
    }

    /** 一步到位：解析切片方法并切分 */
    public static List<String> split(String methodName, String text, Resource resource, ChunkParams params) {
        return of(ChunkMethod.fromName(methodName)).split(text, resource, params);
    }
}
