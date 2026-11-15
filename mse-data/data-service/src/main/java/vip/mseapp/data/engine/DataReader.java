package vip.mseapp.data.engine;

import java.sql.SQLException;
import java.util.List;

/**
 * 数据通道读取器：从来源分批拉取行。
 *
 * <p>一期为 JDBC 实现（keyset/流式读取）；三期可扩展 ClickHouse/CDC 等实现接入更多来源。
 */
public interface DataReader extends AutoCloseable {

    /** 列清单（顺序与行数据一致） */
    List<ColumnMeta> columnMeta() throws SQLException;

    /** 下一批行（行内值顺序与 columnMeta 一致）；读完返回空列表 */
    List<Object[]> nextBatch() throws SQLException;

    /** 是否已读完全部数据 */
    boolean finished();

    /** 增量模式：本批数据中水位列的最大值（作为新水位回写）；非增量或无数据返回 null */
    String lastWatermarkValue();

    @Override
    void close();
}
