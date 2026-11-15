package vip.mseapp.data.open;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vip.mseapp.core.datasource.TenantRouteResolver;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.config.DataProperties;
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.mapper.DatDatasourceMapper;
import vip.mseapp.data.service.QueryAllowlistService;
import vip.mseapp.ai.api.support.ApiSqlGuard;
import vip.mseapp.data.support.DataConnectorManager;
import vip.mseapp.data.support.MasterDbGuard;
import vip.mseapp.mybatis.datasource.TenantRawConnectionRouter;
import vip.mseapp.ai.api.support.TenantColumnProbe;

import java.math.BigDecimal;
import java.sql.Connection;
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
 * API 查询执行器：防护改写 → 参数化绑定 → 限流执行 → 结果读取。
 *
 * <p>internal（datasource_id=0）：先判定租户模式再选目标库——共享库租户全部走中台库 master 连接；
 * 独立库租户的白名单业务表路由至其租户专属库（业务数据在租户库），纯 ods_ 查询仍走 master
 * （同步引擎只写主库，tenant_id 行级隔离不受影响），同句混查两类表明确报错引导拆分。
 * ods_ 与白名单表均已注入租户条件，白名单表在目标库上探测 tenant_id 列（缺列拒绝执行）。
 * external：走租户注册数据源连接，数据源级隔离。执行超时/行数上限由 API 定义控制。
 */
@Component
public class ApiQueryExecutor {

    private static final JsonMapper PARAM_MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final DataConnectorManager connectorManager;
    private final TenantRawConnectionRouter masterConnections;
    private final DatDatasourceMapper datasourceMapper;
    private final DataProperties properties;
    private final MasterDbGuard masterDbGuard;
    private final ObjectProvider<TenantRouteResolver> resolverProvider;
    private final QueryAllowlistService allowlistService;
    private final String serviceName;

    public ApiQueryExecutor(DataConnectorManager connectorManager, TenantRawConnectionRouter masterConnections,
                            DatDatasourceMapper datasourceMapper, DataProperties properties,
                            MasterDbGuard masterDbGuard,
                            ObjectProvider<TenantRouteResolver> resolverProvider,
                            QueryAllowlistService allowlistService,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${mse.tenant.service-name:${spring.application.name:}}") String serviceName) {
        this.connectorManager = connectorManager;
        this.masterConnections = masterConnections;
        this.datasourceMapper = datasourceMapper;
        this.properties = properties;
        this.masterDbGuard = masterDbGuard;
        this.resolverProvider = resolverProvider;
        this.allowlistService = allowlistService;
        this.serviceName = serviceName == null || serviceName.isBlank() ? "*" : serviceName.trim();
    }

    /** 执行查询并返回结果（行值已做 JSON 友好转换） */
    public QueryResult execute(DatApi api, Map<String, Object> params, boolean paged,
                               Integer pageNum, Integer pageSize, boolean preview) {
        boolean internal = api.getDatasourceId() == null || api.getDatasourceId() == 0;
        Long tenantId = vip.mseapp.core.context.TenantContext.getRequiredTenantId();

        // 1. SQL 防护与租户注入（internal：ods_ 贴源表 + 白名单业务表均注入）
        Set<String> allowlist = internal ? allowlistService.enabledTableNames() : Set.of();
        ApiSqlGuard.GuardedSql guarded = ApiSqlGuard.guard(
                api.getSqlTemplate(), internal, tenantId, allowlist, true);
        List<ParamDef> defs = parseParamDefs(api.getParamsJson());
        List<Object> bindings = bindValues(guarded.paramNames(), defs, params == null ? Map.of() : params);

        // 2. 分页/行数上限
        boolean usePage = paged && Integer.valueOf(1).equals(api.getPageEnabled());
        int limitRows;
        long offset = 0;
        boolean fetchExtra = false;
        if (preview) {
            limitRows = 10;
        } else if (usePage) {
            int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
            limitRows = Math.min(size, api.getMaxRows() == null ? 1000 : api.getMaxRows());
            offset = pageNum == null || pageNum < 1 ? 0 : (long) (pageNum - 1) * limitRows;
            fetchExtra = true;
        } else {
            limitRows = api.getMaxRows() == null ? 1000 : api.getMaxRows();
        }
        String finalSql = ApiSqlGuard.applyLimit(guarded.sql(), offset, limitRows + (fetchExtra ? 1 : 0));

        // 3. 连接与执行
        long start = System.currentTimeMillis();
        try {
            if (internal) {
                try (Connection conn = internalConnection(guarded.tables(), allowlist, tenantId)) {
                    verifyWhitelistTenantColumn(conn, guarded.tables(), allowlist);
                    return run(conn, finalSql, bindings, limitRows, fetchExtra,
                            api.getTimeoutMs(), start);
                }
            }
            DatDatasource ds = connectorLookup(api.getDatasourceId());
            // 执行期兜底：登记护栏上线前的存量数据源若指向平台主库，同样拒绝
            masterDbGuard.assertNotMaster(ds);
            try (Connection conn = connectorManager.borrow(ds)) {
                return run(conn, finalSql, bindings, limitRows, fetchExtra,
                        api.getTimeoutMs(), start);
            }
        } catch (SQLException e) {
            throw new ServiceException(500, "查询执行失败：" + e.getMessage());
        }
    }

    /**
     * internal 连接选择（先判模式，再选库）：共享库租户恒走 master；
     * 独立库租户的白名单业务表路由至租户专属库，纯 ods_ 查询仍走 master
     * （贴源表由同步引擎写入主库），同句混查两类表报错引导拆分。
     */
    private Connection internalConnection(List<String> tables, Set<String> allowlist,
                                          Long tenantId) throws SQLException {
        String dsKey = resolveDsKey(tenantId);
        boolean hasWhitelist = tables.stream()
                .anyMatch(t -> allowlist.contains(t.toLowerCase(Locale.ROOT)));
        if (dsKey == null || dsKey.isBlank() || !hasWhitelist) {
            return masterConnections.open(ApiQueryExecutor.class);
        }
        boolean hasOds = tables.stream()
                .anyMatch(t -> t.toLowerCase(Locale.ROOT).startsWith("ods_"));
        if (hasOds) {
            throw new ServiceException(400, "独立库租户的 ods_ 贴源表在中台库、白名单业务表在租户专属库，"
                    + "不支持同句混查，请拆分为两条查询");
        }
        return tenantConnection(dsKey);
    }

    /** 租户路由数据源 key（null/空 = 共享库；框架未装配解析器时按恒共享库处理） */
    private String resolveDsKey(Long tenantId) {
        TenantRouteResolver resolver = resolverProvider.getIfAvailable();
        return resolver == null ? null : resolver.resolveRoute(tenantId, serviceName).getDsKey();
    }

    /** 取租户专属库连接（dynamic-datasource 按租户 ds_key 路由；取不到按业务错误拒绝，不静默换库） */
    private Connection tenantConnection(String dsKey) throws SQLException {
        try {
            return masterConnections.routing().getDataSource(dsKey).getConnection();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(400, "租户独立库数据源未配置或不可用：dsKey=" + dsKey);
        }
    }

    /**
     * 白名单业务表必须在目标库上存在且含 tenant_id 列（租户条件注入的前提）：
     * 共享库租户在主库上探测，独立库租户在其专属库上探测；缺列/表不存在均拒绝执行。
     */
    private void verifyWhitelistTenantColumn(Connection conn, List<String> tables,
                                             Set<String> allowlist) throws SQLException {
        for (String table : tables) {
            if (!allowlist.contains(table.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!TenantColumnProbe.hasTenantColumn(conn, table)) {
                throw new ServiceException(400, "白名单表 " + table
                        + " 在目标库中不存在或缺少 tenant_id 列，无法执行租户数据过滤"
                        + "（跨租户隔离前提），请修正表结构或将其移出查询白名单");
            }
        }
    }

    private QueryResult run(Connection conn, String sql, List<Object> bindings, int limitRows,
                            boolean fetchExtra, Integer timeoutMs, long start) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(Math.max(1, (timeoutMs == null ? 10000 : timeoutMs) / 1000));
            for (int i = 0; i < bindings.size(); i++) {
                ps.setObject(i + 1, bindings.get(i));
            }
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
                boolean hasMore = fetchExtra && rs.next();
                return new QueryResult(columns, rows, hasMore,
                        System.currentTimeMillis() - start);
            }
        }
    }

    /** 值转 JSON 友好类型（时间→文本、二进制→占位提示，避免序列化歧义） */
    private Object jsonFriendly(Object value) {
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

    private List<Object> bindValues(List<String> names, List<ParamDef> defs, Map<String, Object> params) {
        Map<String, ParamDef> defByName = new LinkedHashMap<>();
        for (ParamDef def : defs) {
            defByName.put(def.name(), def);
        }
        List<Object> bindings = new ArrayList<>();
        for (String name : names) {
            if (!params.containsKey(name) || params.get(name) == null
                    || "".equals(params.get(name))) {
                ParamDef def = defByName.get(name);
                if (def != null && def.required()) {
                    throw new ServiceException(400, "缺少必填参数：" + name);
                }
                bindings.add(null);
                continue;
            }
            bindings.add(convert(params.get(name), defByName.get(name)));
        }
        return bindings;
    }

    /** 按参数定义的类型做轻量转换；无定义时按原始 JSON 类型直传 */
    private Object convert(Object raw, ParamDef def) {
        String type = def == null ? "auto" : def.type() == null ? "auto" : def.type();
        return switch (type) {
            case "number" -> {
                if (raw instanceof Number) {
                    yield raw;
                }
                try {
                    yield Long.parseLong(raw.toString().trim());
                } catch (NumberFormatException e) {
                    yield new BigDecimal(raw.toString().trim());
                }
            }
            case "boolean" -> raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
            default -> raw;
        };
    }

    /** 参数定义 JSON → 结构化清单（宽松解析，坏定义不致命） */
    public static List<ParamDef> parseParamDefs(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = PARAM_MAPPER.readValue(paramsJson,
                    new tools.jackson.core.type.TypeReference<>() {
                    });
            List<ParamDef> defs = new ArrayList<>();
            for (Map<String, Object> item : list) {
                Object name = item.get("name");
                if (name != null && !name.toString().isBlank()) {
                    defs.add(new ParamDef(name.toString(),
                            item.get("type") == null ? "auto" : item.get("type").toString(),
                            Boolean.TRUE.equals(item.get("required")),
                            item.get("remark") == null ? null : item.get("remark").toString()));
                }
            }
            return defs;
        } catch (Exception e) {
            throw new ServiceException(400, "参数定义 JSON 非法：" + e.getMessage());
        }
    }

    private DatDatasource connectorLookup(Long datasourceId) {
        DatDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new ServiceException(404, "数据源不存在或已被删除");
        }
        // 归属双保险：租户行级隔离关闭（独立库部署 mse.tenant.enabled=false）时仍拒绝跨租户取数
        Long tenantId = vip.mseapp.core.context.TenantContext.getRequiredTenantId();
        if (ds.getTenantId() != null && ds.getTenantId() != 0 && !ds.getTenantId().equals(tenantId)) {
            throw new ServiceException(403, "数据源不属于当前租户，禁止使用");
        }
        if (ds.getStatus() != DatDatasource.STATUS_ENABLED) {
            throw new ServiceException(400, "数据源已停用");
        }
        return ds;
    }

    public record ParamDef(String name, String type, boolean required, String remark) {
    }

    public record QueryResult(List<String> columns, List<Map<String, Object>> rows,
                              boolean hasMore, long costMs) {
    }
}
