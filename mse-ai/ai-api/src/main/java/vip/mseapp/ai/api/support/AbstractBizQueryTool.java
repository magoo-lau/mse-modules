package vip.mseapp.ai.api.support;

import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.mybatis.datasource.TenantRawConnectionRouter;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 业务模块 biz 查询工具基类：本模块业务表的受控 text2sql 直查通道（实时 JDBC 元数据，
 * 无需任何采集登记）。由业务模块子类化并在本模块进程内执行——连接经
 * {@link TenantRawConnectionRouter#open(Class)} 按子类所在包的 @TenantServiceModule
 * 解析本模块世界（独立库租户→租户专属库、共享租户→本模块内置共享库、平台会话→平台库），
 * 数据不出归属服务。
 *
 * <p>三动作：meta_search=实时检索本模块表目录（表名/注释关键字，前缀白名单 ∩ 硬黑名单）；
 * table=查表列清单；execute=受控只读 SELECT（guardBiz 前缀防护 + 逐表探测动态注入
 * tenant_id/deleted=0 + 限行限流）。meta_search 返回空即目录无匹配——最终结论，
 * 提示模型勿换词重试。
 *
 * <p>平台侧通用入口（mse-ai 的 biz_query 路由门面）按模块注册表转发到本工具；
 * 模块自有分析师智能体也可直接绑定本工具（同进程本地调用）。
 */
public abstract class AbstractBizQueryTool implements RemoteAiTool {

    /** meta_search 单次返回表数上限 */
    private static final int SEARCH_LIMIT = 50;

    protected final JsonMapper objectMapper;
    private final TenantRawConnectionRouter masterConnections;
    private final AiBizQueryProperties properties;

    protected AbstractBizQueryTool(JsonMapper objectMapper,
                                   ObjectProvider<TenantRawConnectionRouter> masterConnections,
                                   AiBizQueryProperties properties) {
        this.objectMapper = objectMapper;
        this.masterConnections = masterConnections.getObject();
        this.properties = properties;
    }

    /** 工具编码（如 oa_biz_query；ai_tool.tool_code 全局唯一） */
    protected abstract String toolCode();

    /** 工具名称（面向模型，动宾结构，如「协同办公业务查询」） */
    protected abstract String toolName();

    /** 工具描述（何时调用；基类补齐标准工作流约束后仍建议子类给领域引导） */
    protected abstract String toolDescription();

    /** 模块编码（如 oa；与 AiBizModuleDescriptor.moduleCode 一致） */
    public abstract String moduleCode();

    /** 模块中文名（错误提示用，如「协同办公」） */
    public abstract String moduleName();

    /** 放行业务表前缀（小写，如 List.of("oa_")） */
    protected abstract List<String> tablePrefixes();

    @Override
    public final AiToolDescriptor describe() {
        AiToolDescriptor descriptor = new AiToolDescriptor();
        descriptor.setToolCode(toolCode());
        descriptor.setName(toolName());
        descriptor.setDescription(toolDescription()
                + "先按 action=meta_search 检索本模块业务表目录与列清单（keyword 传数组可一次检索多个关键字），"
                + "再按 action=execute 执行受控只读 SELECT："
                + "系统自动按租户过滤（跨租户隔离）、自动剔除已删除数据（deleted=0）、自动分页限行；"
                + "系统表与凭证表（sys_/ai_ 等）不可查询。写 SQL 前先用 meta_search 确认表和列名。"
                + "meta_search 返回空即目录中无匹配业务表——这是最终结论，请勿更换关键字重试，直接如实告知用户。");
        descriptor.setParametersJsonSchema("""
                {"type":"object","properties":{
                  "action":{"type":"string","enum":["meta_search","table","execute"],
                    "description":"meta_search=检索业务表/列元数据；table=指定表名查列清单；execute=执行受控只读 SQL"},
                  "keyword":{"type":"array","items":{"type":"string"},
                    "description":"meta_search 的表名/注释关键字列表（一次传多个，工具内部合并去重返回）；返回空即无匹配业务表，属最终结论，勿再更换关键字重试"},
                  "table":{"type":"string","description":"table 动作指定表名，返回该表的列清单（写 SQL 前先确认列名）"},
                  "sql":{"type":"string","description":"execute 的只读 SELECT 语句（不含分页，系统自动追加）"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最大受系统行数上限约束"}
                },"required":["action"]}""");
        return descriptor;
    }

    @Override
    public final AiToolResult invoke(String argumentsJson) {
        try {
            Map<String, Object> args = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
                    });
            String action = String.valueOf(args.getOrDefault("action", ""));
            return switch (action) {
                case "meta_search" -> metaSearch(args);
                case "table" -> tableDetail(args);
                case "execute" -> execute(args);
                default -> AiToolResult.fail("未知的 action：" + action);
            };
        } catch (ServiceException e) {
            return AiToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("工具执行失败：" + e.getMessage());
        }
    }

    /** 元数据实时检索：仅暴露可查业务表的目录（本模块前缀白名单 ∩ 硬黑名单之外全滤除） */
    private AiToolResult metaSearch(Map<String, Object> args) throws SQLException {
        List<String> keywords = keywords(args);
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        try (Connection conn = connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String[] catalogSchema = catalogSchema(conn, meta);
            try (ResultSet tables = meta.getTables(catalogSchema[0], catalogSchema[1], "%", new String[]{"TABLE"})) {
                while (tables.next() && merged.size() < SEARCH_LIMIT) {
                    String tableName = tables.getString("TABLE_NAME");
                    if (tableName == null || !queryable(tableName) || !matchKeyword(tableName, tables.getString("REMARKS"), keywords)) {
                        continue;
                    }
                    // 多关键字命中按表名合并去重（一次调用覆盖多个候选词，消除换词重试）
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("table", tableName);
                    row.put("comment", tables.getString("REMARKS"));
                    merged.putIfAbsent(tableName.toLowerCase(Locale.ROOT), row);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", merged.size());
        result.put("tables", new ArrayList<>(merged.values()));
        if (merged.isEmpty()) {
            // 空结果自带终止语义（不返回任何鼓励继续检索的建议）：
            // 对模型而言这就是最终结论，配合平台侧空结果终止指令双层兜底
            result.put("note", "未检索到匹配业务表。目录检索到此为止：请勿更换关键字再次检索，"
                    + "直接向用户说明未查询到相关业务表。");
        }
        return AiToolResult.ok(toJson(result));
    }

    /** 列清单：写 SQL 前确认表结构（表名大小写不敏感，实时取列名/类型/注释） */
    private AiToolResult tableDetail(Map<String, Object> args) throws SQLException {
        String tableName = args.get("table") == null ? "" : String.valueOf(args.get("table")).trim();
        if (tableName.isEmpty()) {
            throw new ServiceException(400, "table 不能为空");
        }
        if (!queryable(tableName)) {
            return AiToolResult.fail("未找到该表或该表不在可查范围（仅本模块业务前缀表开放）。不要重试同一表名，"
                    + "请改用 action=meta_search + keyword 重新检索表目录后再试");
        }
        try (Connection conn = connection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String[] catalogSchema = catalogSchema(conn, meta);
            String actualName = null;
            String tableComment = null;
            try (ResultSet tables = meta.getTables(catalogSchema[0], catalogSchema[1], "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    if (tableName.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                        actualName = tables.getString("TABLE_NAME");
                        tableComment = tables.getString("REMARKS");
                        break;
                    }
                }
            }
            if (actualName == null) {
                return AiToolResult.fail("未找到该表（" + tableName + "）。不要重试同一表名，"
                        + "请改用 action=meta_search + keyword 重新检索表目录后再试");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("table", actualName);
            result.put("tableComment", tableComment);
            List<Map<String, Object>> columns = new ArrayList<>();
            try (ResultSet rs = meta.getColumns(catalogSchema[0], catalogSchema[1], actualName, "%")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("column", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("TYPE_NAME"));
                    col.put("comment", rs.getString("REMARKS"));
                    columns.add(col);
                }
            }
            result.put("columns", columns);
            return AiToolResult.ok(toJson(result));
        }
    }

    /** 受控 SQL 执行：guardBiz 前缀防护 → 逐表探测动态注入租户/deleted 口径 → 限流分页 */
    private AiToolResult execute(Map<String, Object> args) throws SQLException {
        String sql = String.valueOf(args.getOrDefault("sql", ""));
        if (sql.isBlank()) {
            throw new ServiceException("sql 不能为空");
        }
        long tenantId = TenantContext.getRequiredTenantId();
        int maxRows = properties.getMaxRows();
        int limitRows = intArg(args, "pageSize", 20);
        limitRows = Math.min(Math.max(limitRows, 1), maxRows);
        long offset = Math.max(longArg(args, "pageNum", 1) - 1, 0) * limitRows;
        ApiSqlGuard.GuardedSql guarded = ApiSqlGuard.guardBiz(sql,
                properties.denyPrefixSet(), properties.denyTableSet(), allowPrefixSet());
        String limitedSql = ApiSqlGuard.applyLimit(guarded.sql(), offset, limitRows + 1);
        long start = System.currentTimeMillis();
        try (Connection conn = connection()) {
            Set<String> tenantTables = new LinkedHashSet<>();
            Set<String> notDeletedTables = new LinkedHashSet<>();
            for (String table : guarded.tables()) {
                if (TenantColumnProbe.hasTenantColumn(conn, table)) {
                    tenantTables.add(table.toLowerCase(Locale.ROOT));
                }
                if (TenantColumnProbe.hasDeletedColumn(conn, table)) {
                    notDeletedTables.add(table.toLowerCase(Locale.ROOT));
                }
            }
            String finalSql = ApiSqlGuard.injectBizPredicates(limitedSql, tenantId, tenantTables, notDeletedTables);
            try (PreparedStatement ps = conn.prepareStatement(finalSql)) {
                ps.setQueryTimeout(Math.max(1, properties.getTimeoutMillis() / 1000));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next() && rows.size() < limitRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnLabel(i), jsonFriendly(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                    boolean hasMore = rs.next();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("columns", columns);
                    out.put("rows", rows);
                    out.put("hasMore", hasMore);
                    out.put("costMs", System.currentTimeMillis() - start);
                    return AiToolResult.ok(toJson(out));
                }
            }
        }
    }

    /** 本模块世界连接（路由语义见类注释；调用方负责关闭） */
    private Connection connection() throws SQLException {
        return masterConnections.open(getClass());
    }

    /** 方言定位：mysql 走 catalog、dm 走 schema、PG 协议族（postgres/kingbase）默认 public */
    private String[] catalogSchema(Connection conn, DatabaseMetaData meta) throws SQLException {
        String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("mysql")) {
            return new String[]{conn.getCatalog(), null};
        }
        if (product.contains("dm")) {
            return new String[]{null, conn.getSchema()};
        }
        return new String[]{null, conn.getSchema() != null ? conn.getSchema() : "public"};
    }

    /** 前缀白名单放行（模块声明）且未命中硬黑名单（安全底线）才对模型可见 */
    private boolean queryable(String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        return !properties.isDenied(lower)
                && allowPrefixSet().stream().anyMatch(lower::startsWith);
    }

    private Set<String> allowPrefixSet() {
        Set<String> out = new LinkedHashSet<>();
        if (tablePrefixes() != null) {
            for (String prefix : tablePrefixes()) {
                if (prefix != null && !prefix.isBlank()) {
                    out.add(prefix.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    /** 关键字命中：表名（小写不敏感）或注释包含任一关键字；空关键字列表不放大（返回空目录） */
    private boolean matchKeyword(String tableName, String comment, List<String> keywords) {
        if (keywords.isEmpty()) {
            return false;
        }
        String lowerName = tableName.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lowerName.contains(keyword.toLowerCase(Locale.ROOT))
                    || (comment != null && comment.contains(keyword))) {
                return true;
            }
        }
        return false;
    }

    /** 检索关键字归一：兼容单个字符串（旧调用）与字符串数组（推荐，一次多词） */
    private List<String> keywords(Map<String, Object> args) {
        Object value = args.get("keyword");
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> {
                String keyword = item == null ? "" : String.valueOf(item).trim();
                if (!keyword.isEmpty()) {
                    out.add(keyword);
                }
            });
        } else if (value != null && !String.valueOf(value).isBlank()) {
            out.add(String.valueOf(value).trim());
        }
        return out;
    }

    /** 值转 JSON 友好类型（时间→文本、二进制→占位提示，避免序列化歧义） */
    private static Object jsonFriendly(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp || value instanceof java.sql.Date
                || value instanceof java.sql.Time || value instanceof java.time.LocalDateTime
                || value instanceof java.time.LocalDate || value instanceof java.time.LocalTime) {
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

    private int intArg(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long longArg(Map<String, Object> args, String key, long fallback) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
