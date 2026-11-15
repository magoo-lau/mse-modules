package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.entity.DatMetaColumn;
import vip.mseapp.data.entity.DatMetaTable;
import vip.mseapp.data.mapper.DatMetaColumnMapper;
import vip.mseapp.data.mapper.DatMetaTableMapper;
import vip.mseapp.data.support.DataConnectorManager;
import vip.mseapp.mybatis.datasource.TenantRawConnectionRouter;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 元数据与数据地图：从注册数据源采集表/列清单（JDBC DatabaseMetaData），
 * 以及中台库自身（0 号数据源）的贴源层与业务表登记；提供检索、详情与采样预览。
 */
@Service
public class MetaService {

    /** 采样预览行数上限 */
    private static final int SAMPLE_LIMIT = 100;

    /** 导出行数上限 */
    private static final int EXPORT_LIMIT = 50_000;

    private final DatMetaTableMapper tableMapper;
    private final DatMetaColumnMapper columnMapper;
    private final DataConnectorManager connectorManager;
    private final DatasourceService datasourceService;
    private final TenantRawConnectionRouter masterConnections;

    public MetaService(DatMetaTableMapper tableMapper, DatMetaColumnMapper columnMapper,
                       DataConnectorManager connectorManager, DatasourceService datasourceService,
                       TenantRawConnectionRouter masterConnections) {
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.connectorManager = connectorManager;
        this.datasourceService = datasourceService;
        this.masterConnections = masterConnections;
    }

    // ---------------- 采集 ----------------

    /** 采集指定数据源（tenant 自有库）的表/列 */
    @Transactional(rollbackFor = Exception.class)
    public int collectFromDatasource(Long datasourceId) {
        DatDatasource ds = datasourceService.getRaw(datasourceId);
        try (Connection conn = connectorManager.openDirect(ds)) {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            String catalog = product.contains("mysql") ? ds.getDbName() : null;
            String schemaPattern;
            if (product.contains("mysql")) {
                schemaPattern = null;
            } else if (product.contains("dm")) {
                schemaPattern = ds.getDbName().toUpperCase(Locale.ROOT);
            } else if (product.contains("postgres")) {
                schemaPattern = conn.getSchema() != null ? conn.getSchema() : "public";
            } else {
                // 金仓与 PG 协议族一致，默认 public
                schemaPattern = "public";
            }
            return collect(conn.getMetaData(), catalog, schemaPattern, ds.getId(),
                    DatMetaTable.CATEGORY_SOURCE, ds.getDbName(), false);
        } catch (SQLException e) {
            throw new ServiceException(500, "元数据采集失败：" + e.getMessage());
        }
    }

    /** 登记中台贴源层（ods_ 前缀物理表，datasource_id=0） */
    @Transactional(rollbackFor = Exception.class)
    public int collectOds() {
        try (Connection conn = masterConnections.open(MetaService.class)) {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            String catalog = product.contains("mysql") ? conn.getCatalog() : null;
            String schemaPattern = product.contains("mysql") ? null
                    : product.contains("dm") ? conn.getSchema() : "public";
            return collect(conn.getMetaData(), catalog, schemaPattern, 0L,
                    DatMetaTable.CATEGORY_ODS, null, true);
        } catch (SQLException e) {
            throw new ServiceException(500, "贴源层登记失败：" + e.getMessage());
        }
    }

    /** 登记中台库业务表（共享库形态同步取数正门；datasource_id=0、category=source，自动租户过滤在执行期） */
    @Transactional(rollbackFor = Exception.class)
    public int collectMasterSource() {
        try (Connection conn = masterConnections.open(MetaService.class)) {
            DatabaseMetaData meta = conn.getMetaData();
            String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
            String catalog = product.contains("mysql") ? conn.getCatalog() : null;
            String schemaPattern = product.contains("mysql") ? null
                    : product.contains("dm") ? conn.getSchema() : "public";
            return collect(conn.getMetaData(), catalog, schemaPattern, 0L,
                    DatMetaTable.CATEGORY_SOURCE, null, false);
        } catch (SQLException e) {
            throw new ServiceException(500, "中台库业务表登记失败：" + e.getMessage());
        }
    }

    private int collect(DatabaseMetaData meta, String catalog, String schemaPattern,
                        Long datasourceId, String category, String schemaName, boolean odsOnly) throws SQLException {
        // 旧采集数据整段重建（按 数据源+分类 圈定，避免 0 号数据源下 ods/source 两类互相清理），收集本轮表名供差集清理
        List<DatMetaTable> oldTables = tableMapper.selectList(
                new LambdaQueryWrapper<DatMetaTable>()
                        .eq(DatMetaTable::getDatasourceId, datasourceId)
                        .eq(DatMetaTable::getCategory, category));
        java.util.Set<String> currentNames = new java.util.HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        try (ResultSet tables = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (odsOnly && !tableName.toLowerCase(Locale.ROOT).startsWith("ods_")) {
                    continue;
                }
                currentNames.add(tableName);
                DatMetaTable entity = new DatMetaTable();
                entity.setDatasourceId(datasourceId);
                entity.setTableSchema(schemaName != null ? schemaName : tables.getString("TABLE_SCHEM"));
                entity.setTableName(tableName);
                entity.setCategory(category);
                entity.setTableComment(tables.getString("REMARKS"));
                entity.setCollectedTime(now);
                tableMapper.insert(entity);
                collectColumns(meta, catalog, schemaPattern, tableName, entity.getId());
                count++;
            }
        }
        // 清理本轮已不存在的旧表
        for (DatMetaTable old : oldTables) {
            if (!currentNames.contains(old.getTableName())) {
                deleteTableDeep(old.getId());
            }
        }
        return count;
    }

    private void collectColumns(DatabaseMetaData meta, String catalog, String schemaPattern,
                                String tableName, Long metaTableId) throws SQLException {
        try (ResultSet columns = meta.getColumns(catalog, schemaPattern, tableName, "%")) {
            while (columns.next()) {
                DatMetaColumn column = new DatMetaColumn();
                column.setMetaTableId(metaTableId);
                column.setColumnName(columns.getString("COLUMN_NAME"));
                column.setDataType(columns.getString("TYPE_NAME"));
                column.setNullable(columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable ? 1 : 0);
                column.setColumnComment(columns.getString("REMARKS"));
                column.setOrdinal(columns.getInt("ORDINAL_POSITION"));
                columnMapper.insert(column);
            }
        }
    }

    private void deleteTableDeep(Long metaTableId) {
        columnMapper.delete(new LambdaQueryWrapper<DatMetaColumn>()
                .eq(DatMetaColumn::getMetaTableId, metaTableId));
        tableMapper.deleteById(metaTableId);
    }

    // ---------------- 数据地图查询 ----------------

    public PageResult<DatMetaTable> pageTables(long pageNum, long pageSize, String keyword,
                                               String category, Long datasourceId) {
        LambdaQueryWrapper<DatMetaTable> wrapper = new LambdaQueryWrapper<DatMetaTable>()
                .and(keyword != null && !keyword.isBlank(), w -> w
                        .like(DatMetaTable::getTableName, keyword)
                        .or().like(DatMetaTable::getTableComment, keyword))
                .eq(category != null && !category.isBlank(), DatMetaTable::getCategory, category)
                .eq(datasourceId != null, DatMetaTable::getDatasourceId, datasourceId)
                .orderByAsc(DatMetaTable::getTableName);
        Page<DatMetaTable> page = tableMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    public TableDetail tableDetail(Long metaTableId) {
        DatMetaTable table = tableMapper.selectById(metaTableId);
        if (table == null) {
            throw new ServiceException(404, "表不存在: " + metaTableId);
        }
        return new TableDetail(table, loadColumns(metaTableId));
    }

    /** 按表名精确查表详情（数据中台 AI 工具「列清单」通道：表名比长编号对模型更可靠） */
    public TableDetail tableByName(String tableName) {
        DatMetaTable table = tableMapper.selectOne(new LambdaQueryWrapper<DatMetaTable>()
                .eq(DatMetaTable::getTableName, tableName)
                .last("LIMIT 1"));
        if (table == null) {
            throw new ServiceException(404, "表不存在: " + tableName);
        }
        return new TableDetail(table, loadColumns(table.getId()));
    }

    private List<DatMetaColumn> loadColumns(Long metaTableId) {
        return columnMapper.selectList(new LambdaQueryWrapper<DatMetaColumn>()
                .eq(DatMetaColumn::getMetaTableId, metaTableId)
                .orderByAsc(DatMetaColumn::getOrdinal));
    }

    /** 采样预览（内部 ods 表自动带租户条件；上限 SAMPLE_LIMIT 行） */
    public SampleData sample(Long metaTableId) {
        return rows(metaTableId, SAMPLE_LIMIT);
    }

    /** 导出取数（内部 ods 表自动带租户条件；limit 由调用方限制上限） */
    public SampleData rows(Long metaTableId, int limit) {
        DatMetaTable table = tableMapper.selectById(metaTableId);
        if (table == null) {
            throw new ServiceException(404, "表不存在");
        }
        String name = table.getTableName();
        if (!name.matches("^[A-Za-z_][A-Za-z0-9_]{0,127}$")) {
            throw new ServiceException(400, "表名非法");
        }
        String sql = "SELECT * FROM " + name
                + (table.getDatasourceId() == 0L && tableHasTenantColumn(name)
                        ? " WHERE tenant_id = " + TenantContext.getRequiredTenantId() : "")
                + " LIMIT " + Math.min(limit, EXPORT_LIMIT);
        try {
            if (table.getDatasourceId() == 0L) {
                try (Connection conn = masterConnections.open(MetaService.class);
                     PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {
                    return readSample(rs);
                }
            }
            DatDatasource ds = datasourceService.getRaw(table.getDatasourceId());
            try (Connection conn = connectorManager.borrow(ds);
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                return readSample(rs);
            }
        } catch (SQLException e) {
            throw new ServiceException(500, "取数失败：" + e.getMessage());
        }
    }

    /** 中台库物理表是否含 tenant_id 列（贴源表恒有；业务表/全局表探测决定采样是否按租户过滤） */
    private boolean tableHasTenantColumn(String tableName) {
        try (Connection conn = masterConnections.open(MetaService.class);
             ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, "tenant_id")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private SampleData readSample(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            columns.add(meta.getColumnLabel(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(meta.getColumnLabel(i), normalize(rs.getObject(i)));
            }
            rows.add(row);
        }
        return new SampleData(columns, rows);
    }

    /** 值标准化：时间→文本、二进制→占位、BigDecimal→plain（同时满足 JSON 序列化与 EasyExcel 导出） */
    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp || value instanceof java.sql.Date
                || value instanceof java.sql.Time || value instanceof java.time.LocalDateTime
                || value instanceof java.time.LocalDate || value instanceof java.time.LocalTime
                || value instanceof java.time.OffsetDateTime) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return "<binary " + bytes.length + "B>";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value;
    }

    public record TableDetail(DatMetaTable table, List<DatMetaColumn> columns) {
    }

    public record SampleData(List<String> columns, List<Map<String, Object>> rows) {
    }
}
