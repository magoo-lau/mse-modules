package vip.mseapp.ai.kb.chunk;

/**
 * 切片方法（对标 RAGFlow chunk templates 的务实子集）。
 *
 * <ul>
 *   <li>GENERAL 通用：分隔符感知的句子聚合 + 重叠（绝大多数文档的默认选择）</li>
 *   <li>QA 问答对：Q/A 文本标记或两列 CSV/Excel，每个问答对一个切片</li>
 *   <li>TABLE 表格：CSV/Excel 表头 + 数据行，一行一切片（"列名: 值"摘要文本）</li>
 *   <li>BOOK 书籍：标题层级（Markdown # / 中文章节）切分小节，小节内再按通用规则聚合</li>
 *   <li>ONE 整篇：整篇文档一个切片（短文档 / 全文检索场景）</li>
 * </ul>
 */
public enum ChunkMethod {

    GENERAL, QA, TABLE, BOOK, ONE;

    /** 按名解析，空/未知回落 GENERAL */
    public static ChunkMethod fromName(String name) {
        if (name == null || name.isBlank()) {
            return GENERAL;
        }
        for (ChunkMethod method : values()) {
            if (method.name().equalsIgnoreCase(name.trim())) {
                return method;
            }
        }
        return GENERAL;
    }
}
