package vip.mseapp.data.engine;

import vip.mseapp.core.exception.ServiceException;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 读取器：源查询统一包一层 {@code SELECT * FROM (core) _mse_src}，
 * 增量模式在外层追加 {@code WHERE _mse_src.inc > ? ORDER BY _mse_src.inc}（keyset 拉取）。
 *
 * <p>MySQL 走行级流式（fetchSize=Integer.MIN_VALUE）避免大表整包载入内存；
 * 达梦/金仓按批 fetchSize 流式。
 */
public class JdbcReader implements DataReader {

    private final Connection connection;
    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private final List<ColumnMeta> columns;
    private final String incrColumn;
    private final int batchSize;

    private String lastWatermark;
    private boolean finished;

    public JdbcReader(Connection connection, String coreQuery, String incrColumn,
                      Object watermark, int batchSize) throws SQLException {
        this.connection = connection;
        this.incrColumn = incrColumn;
        this.batchSize = batchSize;
        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(coreQuery)
                .append(") _mse_src");
        if (incrColumn != null && !incrColumn.isBlank()) {
            sql.append(" WHERE _mse_src.").append(incrColumn).append(" > ?")
                    .append(" ORDER BY _mse_src.").append(incrColumn);
        }
        statement = connection.prepareStatement(sql.toString(),
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        if (incrColumn != null && !incrColumn.isBlank()) {
            statement.setObject(1, watermark);
        }
        String product = productName();
        if ("MySQL".equalsIgnoreCase(product)) {
            statement.setFetchSize(Integer.MIN_VALUE);
        } else {
            statement.setFetchSize(batchSize);
        }
        if (product.toLowerCase().contains("postgres")) {
            // PG 协议族（PostgreSQL/金仓）游标生效前提：关闭自动提交（Hikari 归还时会重置）
            connection.setAutoCommit(false);
        }
        resultSet = statement.executeQuery();
        columns = readColumns(resultSet.getMetaData());
    }

    @Override
    public List<ColumnMeta> columnMeta() {
        return columns;
    }

    @Override
    public List<Object[]> nextBatch() throws SQLException {
        if (finished) {
            return List.of();
        }
        int columnCount = columns.size();
        List<Object[]> batch = new ArrayList<>(batchSize);
        while (batch.size() < batchSize && resultSet.next()) {
            Object[] row = new Object[columnCount];
            for (int i = 0; i < columnCount; i++) {
                row[i] = resultSet.getObject(i + 1);
            }
            batch.add(row);
            if (incrColumn != null && !incrColumn.isBlank()) {
                lastWatermark = String.valueOf(resultSet.getObject(incrColumn));
            }
        }
        if (batch.size() < batchSize) {
            finished = true;
        }
        return batch;
    }

    @Override
    public boolean finished() {
        return finished;
    }

    @Override
    public String lastWatermarkValue() {
        return lastWatermark;
    }

    @Override
    public void close() {
        try (Connection c = connection; PreparedStatement s = statement; ResultSet r = resultSet) {
            // try-with-resources 统一关闭
        } catch (SQLException e) {
            // 关闭失败不影响主流程
        }
    }

    private String productName() throws SQLException {
        return connection.getMetaData().getDatabaseProductName();
    }

    private List<ColumnMeta> readColumns(ResultSetMetaData meta) throws SQLException {
        List<ColumnMeta> list = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            list.add(new ColumnMeta(meta.getColumnLabel(i), meta.getColumnType(i),
                    meta.getPrecision(i), meta.getScale(i)));
        }
        if (list.isEmpty()) {
            throw new ServiceException(400, "来源查询未返回任何列");
        }
        return list;
    }

    /** 水位文本 → 绑定值（数值用数值类型，其余按字符串交给驱动转换） */
    public static Object parseWatermark(String watermark) {
        if (watermark == null || watermark.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(watermark.trim());
        } catch (NumberFormatException e) {
            try {
                return new BigDecimal(watermark.trim());
            } catch (NumberFormatException e2) {
                return watermark.trim();
            }
        }
    }
}
