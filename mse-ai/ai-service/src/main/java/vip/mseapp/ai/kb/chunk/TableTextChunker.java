package vip.mseapp.ai.kb.chunk;

import org.springframework.core.io.Resource;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格切片器：CSV/Excel 每行数据一个切片。
 *
 * <p>首行作表头，数据行渲染为"表头: 值；表头: 值"的摘要文本——单行自含语义，
 * 向量检索"按值找行"的召回质量远好于整表一片。Excel 多 sheet 依次处理。
 */
public class TableTextChunker implements TextChunker {

    @Override
    public ChunkMethod method() {
        return ChunkMethod.TABLE;
    }

    @Override
    public List<String> split(String text, Resource resource, ChunkParams params) {
        String fileName = resource == null ? null : resource.getFilename();
        if (fileName == null || !TabularReader.supports(fileName)) {
            throw new ServiceException(400, "表格切片（table）仅支持 CSV/Excel 文件");
        }
        List<List<String>> rows = TabularReader.read(resource, fileName);
        if (rows == null || rows.size() < 2) {
            throw new ServiceException(400, "表格未解析出数据行（需表头 + 至少一行数据）");
        }
        List<String> headers = rows.get(0);
        List<String> chunks = new ArrayList<>(rows.size() - 1);
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < headers.size() && c < row.size(); c++) {
                if (row.get(c).isEmpty()) {
                    continue;
                }
                if (line.length() > 0) {
                    line.append("；");
                }
                line.append(headers.get(c)).append(": ").append(row.get(c));
            }
            if (line.length() > 0) {
                chunks.add(line.toString());
            }
        }
        if (chunks.isEmpty()) {
            throw new ServiceException(400, "表格未解析出数据行（需表头 + 至少一行数据）");
        }
        return chunks;
    }
}
