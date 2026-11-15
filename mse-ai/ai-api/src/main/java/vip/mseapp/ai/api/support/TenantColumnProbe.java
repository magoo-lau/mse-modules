package vip.mseapp.ai.api.support;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 目标库列探测：判断表是否含指定列（租户条件注入的前提；业务库档另探测
 * deleted 列做逻辑删除口径）。多种大小写形态依次尝试，兼容大小写敏感库；
 * 表不存在时同样返回 false。
 */
public final class TenantColumnProbe {

    private static final String TENANT_COLUMN = "tenant_id";

    private static final String DELETED_COLUMN = "deleted";

    private TenantColumnProbe() {
    }

    public static boolean hasTenantColumn(Connection conn, String table) throws SQLException {
        return hasColumn(conn, table, TENANT_COLUMN);
    }

    public static boolean hasDeletedColumn(Connection conn, String table) throws SQLException {
        return hasColumn(conn, table, DELETED_COLUMN);
    }

    public static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (String candidate : new String[]{table, table.toLowerCase(Locale.ROOT),
                table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = meta.getColumns(null, null, candidate, column)) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}
