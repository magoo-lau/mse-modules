package vip.mseapp.ai.kb.chunk;

import org.springframework.core.io.Resource;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * 问答对切片器：每个问答对一个切片，检索时按问题命中、返回答案。
 *
 * <p>两种来源：① CSV/Excel 两列（问题,答案）；② 文本 Q:/A:、问：/答： 标记（答案可跨行）。
 * 切片正文渲染为"问：…\n答：…"，问题前置保证向量侧与关键词侧都能按问题匹配。
 */
public class QaTextChunker implements TextChunker {

    @Override
    public ChunkMethod method() {
        return ChunkMethod.QA;
    }

    @Override
    public List<String> split(String text, Resource resource, ChunkParams params) {
        List<TabularReader.QaPair> pairs;
        if (resource != null && resource.getFilename() != null && TabularReader.supports(resource.getFilename())) {
            pairs = TabularReader.readQaPairs(TabularReader.read(resource, resource.getFilename()));
        } else {
            pairs = TabularReader.parseQaText(text);
        }
        if (pairs.isEmpty()) {
            throw new ServiceException(400, "未识别出问答对（QA 切片要求 CSV/Excel 两列，或文本 Q:/A:、问：/答： 标记）");
        }
        List<String> chunks = new ArrayList<>(pairs.size());
        for (TabularReader.QaPair pair : pairs) {
            chunks.add(pair.render());
        }
        return chunks;
    }
}
