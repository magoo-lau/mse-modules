package vip.mseapp.ai.kb.chunk;

import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 书籍切片器：标题层级感知切分。
 *
 * <p>识别 Markdown 标题（# ~ ######）与中文章节标题（第 X 章/节/篇/部/讲），
 * 每个标题开启一个小节；小节内按通用规则（句子聚合 + 重叠）二次切分，
 * 小节边界天然成为切片边界，检索命中时语义完整。
 */
public class BookTextChunker implements TextChunker {

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*$");
    private static final Pattern CN_HEADING = Pattern.compile(
            "^第[一二三四五六七八九十百千零0-9]+[章节篇部讲][\\s：:、.].*$");

    private final GeneralTextChunker general = new GeneralTextChunker();

    @Override
    public ChunkMethod method() {
        return ChunkMethod.BOOK;
    }

    @Override
    public List<String> split(String text, Resource resource, ChunkParams params) {
        List<String> chunks = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (isHeading(line) && section.length() > 0) {
                chunks.addAll(general.split(section.toString(), null, params));
                section.setLength(0);
            }
            section.append(line).append('\n');
        }
        if (!section.isEmpty()) {
            chunks.addAll(general.split(section.toString(), null, params));
        }
        return chunks;
    }

    private static boolean isHeading(String line) {
        String trimmed = line.strip();
        return MD_HEADING.matcher(trimmed).matches() || CN_HEADING.matcher(trimmed).matches();
    }
}
