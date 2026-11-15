package vip.mseapp.data.engine;

import vip.mseapp.core.exception.ServiceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JDBC 写入器：写入中台库 ODS 贴源表。
 *
 * <p>目标表不存在时按源列定义动态建表（LOB 类型按目标库方言：MySQL/金仓 TEXT、达梦 CLOB），
 * 统一附加 {@code tenant_id BIGINT NOT NULL DEFAULT 0}；行级租户隔离，严禁 TRUNCATE。
 */
public class JdbcWriter implements DataWriter {

    private static final String TENANT_COLUMN = "tenant_id";

    private final Connection connection;
    private final String table;
    private final String lobType;
    private final String boolType;

    private PreparedStatement insert;
    private List<ColumnMeta> columns;
    /** 源列自带 tenant_id 时不再追加（值随源数据透传） */
    private boolean sourceHasTenantColumn;

    public JdbcWriter(Connection connection, String table) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        this.connection = connection;
        this.table = table;
        this.lobType = product.contains("dm") ? "CLOB" : "TEXT";
        this.boolType = product.contains("mysql") ? "TINYINT"
                : product.contains("kingbase") ? "BOOLEAN" : "BIT";
    }

    @Override
    public void prepare(List<ColumnMeta> columns, boolean full, String incrColumn, Object watermark)
            throws SQLException {
        this.sourceHasTenantColumn = columns.stream()
                .anyMatch(c -> "tenant_id".equalsIgnoreCase(c.name()));
        this.columns = columns;
        ensureTable(columns);
    }

    /**
     * 清空本租户目标行（full=全部行；incr=水位之后行），保证写入幂等。
     * 独立于 prepare：调用方必须已持有租户 ID。
     */
    public void clearTenantRows(long tenantId, String incrColumn, Object watermark) throws SQLException {
        boolean incr = incrColumn != null && !incrColumn.isBlank();
        String sql = incr
                ? "DELETE FROM " + table + " WHERE " + TENANT_COLUMN + " = ? AND " + incrColumn + " > ?"
                : "DELETE FROM " + table + " WHERE " + TENANT_COLUMN + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, tenantId);
            if (incr) {
                ps.setObject(2, watermark != null ? watermark : Long.MIN_VALUE);
            }
            ps.executeUpdate();
        }
        connection.commit();
    }

    @Override
    public int write(List<Object[]> rows, long tenantId) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        if (insert == null) {
            insert = buildInsert(columns);
        }
        // 源列带 tenant_id 时行数据本身包含该列（引擎已按租户过滤），不再追加绑定
        int boundLength = sourceHasTenantColumn ? columns.size() : columns.size() + 1;
        for (Object[] row : rows) {
            for (int i = 0; i < boundLength; i++) {
                Object value = sourceHasTenantColumn ? row[i] : (i < columns.size() ? row[i] : tenantId);
                insert.setObject(i + 1, value);
            }
            insert.addBatch();
        }
        int[] results = insert.executeBatch();
        connection.commit();
        int written = 0;
        for (int r : results) {
            written += r > 0 ? r : 1;
        }
        return written;
    }

    @Override
    public void close() {
        try (Connection c = connection; Statement s = insert) {
            if (s != null) {
                s.close();
            }
        } catch (SQLException e) {
            // 关闭失败不影响主流程
        }
    }

    private void ensureTable(List<ColumnMeta> columns) throws SQLException {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            ColumnMeta column = columns.get(i);
            ddl.append(i > 0 ? ", " : "").append(quoteIdentifier(column.name())).append(" ")
                    .append(toDdlType(column));
        }
        if (!sourceHasTenantColumn) {
            ddl.append(", ").append(TENANT_COLUMN).append(" BIGINT NOT NULL DEFAULT 0");
        }
        ddl.append(')');
        try (Statement st = connection.createStatement()) {
            st.execute(ddl.toString());
        }
        connection.commit();
    }

    private PreparedStatement buildInsert(List<ColumnMeta> columns) throws SQLException {
        boolean appendTenant = !sourceHasTenantColumn;
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (");
        StringBuilder marks = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                marks.append(", ");
            }
            sql.append(quoteIdentifier(columns.get(i).name()));
            marks.append('?');
        }
        if (appendTenant) {
            sql.append(", ").append(TENANT_COLUMN);
            marks.append(", ?");
        }
        sql.append(") VALUES (").append(marks).append(')');
        return connection.prepareStatement(sql.toString());
    }

    private String quoteIdentifier(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
            throw new ServiceException(400, "非法列名：" + name);
        }
        return name;
    }

    /** JDBC 类型 → 目标库 DDL 类型（保守映射，兼容 MySQL/达梦/金仓） */
    private String toDdlType(ColumnMeta column) {
        int precision = Math.max(column.precision(), 1);
        int scale = Math.max(column.scale(), 0);
        return switch (column.jdbcType()) {
            case Types.BIGINT -> "BIGINT";
            case Types.INTEGER -> "INTEGER";
            case Types.SMALLINT, Types.TINYINT -> "SMALLINT";
            case Types.BOOLEAN, Types.BIT -> boolType;
            case Types.DECIMAL, Types.NUMERIC -> precision <= 38
                    ? "DECIMAL(" + precision + "," + Math.min(scale, precision) + ")"
                    : "DECIMAL(38,10)";
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> "DOUBLE PRECISION";
            case Types.DATE -> "DATE";
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> "TIME";
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP";
            case Types.CHAR, Types.NCHAR -> "VARCHAR(" + Math.min(precision, 255) + ")";
            case Types.VARCHAR, Types.NVARCHAR -> "VARCHAR(" + Math.min(Math.max(precision, 1), 2000) + ")";
            default -> lobType;
        };
    }
}
