package vip.mseapp.data.service;

import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.ai.api.RemoteAiTool;
import vip.mseapp.ai.api.dto.AiToolDescriptor;
import vip.mseapp.ai.api.dto.AiToolResult;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.mapper.DatDatasourceMapper;
import vip.mseapp.data.open.ApiQueryExecutor;
import vip.mseapp.data.service.MetaService.TableDetail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数据中台智能查询工具门面（mse-ai 平台反向集成：一服务一工具，action 分发）。
 *
 * <p>text2sql 受控通道：模型生成的 SQL 不直接落库——meta_search 供模型检索表/列元数据
 * （表目录/列清单；所有编号以字符串回传并支持按表名查列，避免模型抄写长整型编号出错），
 * execute 经 {@link ApiQueryExecutor} 统一走 ApiSqlGuard SQL 防护 + 租户条件注入 +
 * 强制行数上限（internal 中台库允许 ods_ 前缀表与租户白名单业务表，两类表均注入租户条件；
 * 独立库租户的白名单表自动路由至其租户专属库；external 走租户注册数据源）；
 * datasource_list 供编排画布「数据查询」节点下拉（中台库 + 当前租户已启用数据源）。
 */
@Component
public class DataQueryTools implements RemoteAiTool {

    /** 单次返回行数上限（工具场景从紧） */
    private static final int MAX_ROWS = 200;

    private static final String TOOL_CODE = "data_query";

    private final JsonMapper objectMapper;
    private final MetaService metaService;
    private final ApiQueryExecutor queryExecutor;
    private final DatDatasourceMapper datasourceMapper;
    private final QueryAllowlistService allowlistService;

    public DataQueryTools(JsonMapper objectMapper, MetaService metaService, ApiQueryExecutor queryExecutor,
                          DatDatasourceMapper datasourceMapper, QueryAllowlistService allowlistService) {
        this.objectMapper = objectMapper;
        this.metaService = metaService;
        this.queryExecutor = queryExecutor;
        this.datasourceMapper = datasourceMapper;
        this.allowlistService = allowlistService;
    }

    @Override
    public AiToolDescriptor describe() {
        AiToolDescriptor descriptor = new AiToolDescriptor();
        descriptor.setToolCode(TOOL_CODE);
        descriptor.setName("数据中台智能查询");
        descriptor.setDescription("按 action 检索数据中台的表/列元数据（meta_search），执行受控只读 SQL 查询"
                + "（execute：自动注入租户条件、限行数、只允许 SELECT），或列出可用数据源"
                + "（datasource_list：中台库 + 本租户已启用数据源）。中台库（datasourceId=0）允许查询"
                + " ods_ 前缀贴源表与本租户白名单业务表（见 datasource_list 的 allowedTables），"
                + "两类表均自动按租户过滤。写 SQL 前先用 meta_search 确认表和列名。");
        descriptor.setParametersJsonSchema("""
                {"type":"object","properties":{
                  "action":{"type":"string","enum":["meta_search","execute","datasource_list"],
                    "description":"meta_search=检索表/列元数据；execute=执行受控只读 SQL；datasource_list=列出可用数据源"},
                  "keyword":{"type":"string","description":"meta_search 的表名/注释关键字"},
                  "table":{"type":"string","description":"meta_search 指定表名时返回该表的列清单（推荐方式，表名比长编号更不易出错）"},
                  "metaTableId":{"type":"string","description":"meta_search 指定表编号（字符串，须整体复制不得改写）时返回该表的列清单；优先用 table 表名"},
                  "datasourceId":{"type":"string","description":"execute 的数据源编号（字符串），\\"0\\" 或省略=中台库（ods_ 贴源表 + 白名单业务表，均自动租户过滤）"},
                  "sql":{"type":"string","description":"execute 的只读 SELECT 语句（不含分页，系统自动追加）"},
                  "pageNum":{"type":"integer","description":"页码，默认 1"},
                  "pageSize":{"type":"integer","description":"每页条数，默认 20，最大 200"}
                },"required":["action"]}""");
        return descriptor;
    }

    @Override
    public AiToolResult invoke(String argumentsJson) {
        try {
            Map<String, Object> args = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
                    });
            String action = String.valueOf(args.getOrDefault("action", ""));
            return switch (action) {
                case "meta_search" -> metaSearch(args);
                case "execute" -> AiToolResult.ok(toJson(execute(args)));
                case "datasource_list" -> AiToolResult.ok(toJson(datasourceList()));
                default -> AiToolResult.fail("未知的 action：" + action);
            };
        } catch (ServiceException e) {
            return AiToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return AiToolResult.fail("工具执行失败：" + e.getMessage());
        }
    }

    /** 元数据检索：带 table 表名 / metaTableId 返回列清单（表名优先），否则按关键字分页查表目录。
     * 中台库目录（datasource_id=0）仅暴露可查面（ods_ 贴源表 + 白名单业务表），系统/凭证表结构
     * 不对模型可见；外部数据源目录不在此限（数据源级隔离，仍按原样暴露）。 */
    private AiToolResult metaSearch(Map<String, Object> args) {
        String tableName = args.get("table") == null ? "" : String.valueOf(args.get("table")).trim();
        String tableId = args.get("metaTableId") == null ? "" : String.valueOf(args.get("metaTableId")).trim();
        if (!tableName.isEmpty() || !tableId.isEmpty()) {
            try {
                TableDetail detail = !tableName.isEmpty()
                        ? metaService.tableByName(tableName)
                        : metaService.tableDetail(Long.valueOf(tableId));
                if (!catalogVisible(detail.table(), allowlistService.enabledTableNames())) {
                    return AiToolResult.fail("未找到该表或该表不在可查范围（中台库仅开放 ods_ 贴源表与"
                            + "白名单业务表）。不要重试同一"
                            + (!tableName.isEmpty() ? "表名" : "编号")
                            + "，请改用 action=meta_search + keyword 重新检索表目录后再试");
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("metaTableId", String.valueOf(detail.table().getId()));
                result.put("table", detail.table().getTableName());
                result.put("tableComment", detail.table().getTableComment());
                List<Map<String, Object>> columns = new ArrayList<>();
                detail.columns().forEach(col -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("column", col.getColumnName());
                    row.put("type", col.getDataType());
                    row.put("comment", col.getColumnComment());
                    columns.add(row);
                });
                result.put("columns", columns);
                return AiToolResult.ok(toJson(result));
            } catch (NumberFormatException e) {
                return AiToolResult.fail("表编号格式非法：" + tableId
                        + "。不要重试同一编号，请改用 action=meta_search + keyword 重新检索表目录");
            } catch (ServiceException e) {
                return AiToolResult.fail("未找到该表（" + e.getMessage() + "）。不要重试同一"
                        + (!tableName.isEmpty() ? "表名" : "编号")
                        + "，请改用 action=meta_search + keyword 重新检索表目录后再试");
            }
        }
        String keyword = args.get("keyword") == null ? null : String.valueOf(args.get("keyword"));
        var page = metaService.pageTables(1, 20, keyword, null, null);
        Set<String> allowlist = allowlistService.enabledTableNames();
        List<Map<String, Object>> rows = new ArrayList<>();
        page.records().forEach(t -> {
            if (!catalogVisible(t, allowlist)) {
                return;
            }
            // 长整型编号以字符串回传：避免模型抄写 19 位整数出错，也避免前端 JSON 解析丢精度
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("metaTableId", String.valueOf(t.getId()));
            row.put("table", t.getTableName());
            row.put("comment", t.getTableComment());
            row.put("datasourceId", t.getDatasourceId() == null ? null : String.valueOf(t.getDatasourceId()));
            rows.add(row);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size());
        result.put("tables", rows);
        result.put("tip", "拿不准列名时，用 table（表名，推荐）或 metaTableId 查看该表的列清单；编号较长，须原样整体复制");
        return AiToolResult.ok(toJson(result));
    }

    /** 中台库目录行可见性：外部数据源表不在此限；中台库表仅 ods_ 前缀贴源表与本租户白名单业务表 */
    private boolean catalogVisible(vip.mseapp.data.entity.DatMetaTable table, Set<String> allowlist) {
        if (table == null || table.getTableName() == null) {
            return false;
        }
        if (table.getDatasourceId() == null || table.getDatasourceId() != 0L) {
            return true;
        }
        String lowerName = table.getTableName().toLowerCase(Locale.ROOT);
        return lowerName.startsWith("ods_") || allowlist.contains(lowerName);
    }

    /** 受控 SQL 执行：合成 DatApi 走 ApiQueryExecutor（SQL 防护+租户注入+限流分页） */
    private Map<String, Object> execute(Map<String, Object> args) {
        String sql = String.valueOf(args.getOrDefault("sql", ""));
        if (sql.isBlank()) {
            throw new ServiceException("sql 不能为空");
        }
        DatApi api = new DatApi();
        Object dsId = args.get("datasourceId");
        api.setDatasourceId(dsId == null ? 0L : Long.valueOf(String.valueOf(dsId)));
        api.setSqlTemplate(sql);
        api.setPageEnabled(1);
        api.setMaxRows(MAX_ROWS);
        api.setTimeoutMs(10000);
        long pageNum = longArg(args, "pageNum", 1);
        long pageSize = Math.min(longArg(args, "pageSize", 20), MAX_ROWS);
        ApiQueryExecutor.QueryResult result = queryExecutor.execute(api, Map.of(), true,
                (int) pageNum, (int) pageSize, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", result.columns());
        out.put("rows", result.rows());
        out.put("hasMore", result.hasMore());
        out.put("costMs", result.costMs());
        return out;
    }

    /** 数据源清单：中台库固定在首位（附白名单表清单）+ 当前租户已启用的外部数据源（租户行级隔离自动过滤）；编号以字符串回传防模型抄错 */
    private Map<String, Object> datasourceList() {
        // 缺租户上下文时明确报错，避免静默返回空清单
        TenantContext.getRequiredTenantId();
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> internal = new LinkedHashMap<>();
        internal.put("id", "0");
        internal.put("name", "中台库（ods_ 贴源表 + 白名单业务表，自动按租户过滤）");
        internal.put("type", "internal");
        internal.put("status", 0);
        internal.put("allowedTables", new ArrayList<>(allowlistService.enabledTableNames()));
        list.add(internal);
        datasourceMapper.selectList(new LambdaQueryWrapper<DatDatasource>()
                        .eq(DatDatasource::getStatus, DatDatasource.STATUS_ENABLED)
                        .orderByAsc(DatDatasource::getId))
                .forEach(ds -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", String.valueOf(ds.getId()));
                    row.put("name", ds.getName());
                    row.put("type", ds.getType());
                    row.put("status", ds.getStatus());
                    list.add(row);
                });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasources", list);
        return result;
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
