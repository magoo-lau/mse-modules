package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.entity.DatQualityReport;
import vip.mseapp.data.entity.DatQualityRule;
import vip.mseapp.data.mapper.DatDatasourceMapper;
import vip.mseapp.data.mapper.DatQualityReportMapper;
import vip.mseapp.data.mapper.DatQualityRuleMapper;
import vip.mseapp.ai.api.support.ApiSqlGuard;
import vip.mseapp.data.support.DataConnectorManager;
import vip.mseapp.mybatis.datasource.TenantRawConnectionRouter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据质量：规则 CRUD + 执行引擎 + 报告查询。
 *
 * <p>执行模型：按规则类型构建单条统计 SQL（内部 ods 表自动注入租户条件，外部源表数据源级隔离），
 * 一次查询取回 检测总行数/坏数据行数，落报告并回写规则最近结果。custom_sql 由用户给出台账级
 * 「坏数据 SELECT」，引擎包裹 COUNT(*) 统计（经 ApiSqlGuard 防护）。
 */
@Service
public class QualityService {

    private static final Logger log = LoggerFactory.getLogger(QualityService.class);

    private static final String IDENTIFIER = "^[A-Za-z_][A-Za-z0-9_]{0,127}$";

    private final DatQualityRuleMapper ruleMapper;
    private final DatQualityReportMapper reportMapper;
    private final DatDatasourceMapper datasourceMapper;
    private final DataConnectorManager connectorManager;
    private final TenantRawConnectionRouter masterConnections;

    /** 运行中的规则（内存防重入：cron 周期小于运行时长时后到触发直接让位，镜像 SyncEngine.runningJobs） */
    private final Set<Long> runningRules = ConcurrentHashMap.newKeySet();

    public QualityService(DatQualityRuleMapper ruleMapper, DatQualityReportMapper reportMapper,
                          DatDatasourceMapper datasourceMapper, DataConnectorManager connectorManager,
                          TenantRawConnectionRouter masterConnections) {
        this.ruleMapper = ruleMapper;
        this.reportMapper = reportMapper;
        this.datasourceMapper = datasourceMapper;
        this.connectorManager = connectorManager;
        this.masterConnections = masterConnections;
    }

    // ---------------- 规则 CRUD ----------------

    public PageResult<DatQualityRule> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<DatQualityRule> wrapper = new LambdaQueryWrapper<DatQualityRule>()
                .like(keyword != null && !keyword.isBlank(), DatQualityRule::getName, keyword)
                .eq(status != null, DatQualityRule::getStatus, status)
                .orderByDesc(DatQualityRule::getId);
        Page<DatQualityRule> page = ruleMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    public DatQualityRule get(Long id) {
        DatQualityRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new ServiceException(404, "质量规则不存在");
        }
        return rule;
    }

    public Long create(DatQualityRule rule) {
        validate(rule);
        ruleMapper.insert(rule);
        return rule.getId();
    }

    public void update(DatQualityRule rule) {
        get(rule.getId());
        validate(rule);
        ruleMapper.updateById(rule);
    }

    public void changeStatus(Long id, boolean enable) {
        DatQualityRule rule = get(id);
        rule.setStatus(enable ? DatQualityRule.STATUS_ENABLED : DatQualityRule.STATUS_DISABLED);
        ruleMapper.updateById(rule);
    }

    public void delete(Long id) {
        get(id);
        ruleMapper.deleteById(id);
    }

    // ---------------- 执行 ----------------

    /** 执行一条规则并落报告（手动与调度共用）；运行中重复触发让位（调度静默跳过、手动明确报错）；返回报告 */
    public DatQualityReport run(Long id, String triggerType) {
        DatQualityRule rule = get(id);
        if (!runningRules.add(id)) {
            if ("sched".equals(triggerType)) {
                log.debug("质量规则 {} 运行中，本轮调度触发跳过", id);
                return null;
            }
            throw new ServiceException(409, "质量规则运行中，请稍后再试");
        }
        try {
            long start = System.currentTimeMillis();
            DatQualityReport report = new DatQualityReport();
            report.setRuleId(rule.getId());
            report.setRuleName(rule.getName());
            report.setTriggerType(triggerType);
            report.setCostMs(0L);
            report.setStartTime(LocalDateTime.now());
            report.setStatus(DatQualityReport.STATUS_SUCCESS);
            try {
                doRun(rule, report);
            } catch (Exception e) {
                report.setStatus(DatQualityReport.STATUS_FAILED);
                report.setErrorMsg(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            report.setCostMs(System.currentTimeMillis() - start);
            report.setEndTime(LocalDateTime.now());
            reportMapper.insert(report);

            rule.setLastRunTime(report.getEndTime());
            rule.setLastPass(DatQualityReport.STATUS_SUCCESS.equals(report.getStatus()) ? 1 : 0);
            rule.setLastPassRate(report.getPassRate());
            ruleMapper.updateById(rule);
            return report;
        } finally {
            runningRules.remove(id);
        }
    }

    private void doRun(DatQualityRule rule, DatQualityReport report) throws SQLException {
        boolean internal = rule.getDatasourceId() == null || rule.getDatasourceId() == 0;
        Long tenantId = TenantContext.getRequiredTenantId();
        String table = rule.getTargetTable();
        String where = internal ? " WHERE tenant_id = " + tenantId : "";
        Map<String, Object> config = parseConfig(rule.getRuleConfig());
        String sql = switch (rule.getRuleType()) {
            case DatQualityRule.TYPE_NOT_NULL -> ("SELECT COUNT(*) AS _total, SUM(CASE WHEN "
                    + col(rule.getColumnName()) + " IS NULL OR " + col(rule.getColumnName())
                    + " = '' THEN 1 ELSE 0 END) AS _bad FROM " + table + where);
            case DatQualityRule.TYPE_UNIQUE -> ("SELECT COUNT(*) AS _total, COUNT(*) - COUNT(DISTINCT "
                    + col(rule.getColumnName()) + ") AS _bad FROM " + table + where);
            case DatQualityRule.TYPE_RANGE -> {
                BigDecimal min = numeric(config.get("min"), "min");
                BigDecimal max = numeric(config.get("max"), "max");
                yield ("SELECT COUNT(*) AS _total, SUM(CASE WHEN " + col(rule.getColumnName()) + " < " + min
                        + " OR " + col(rule.getColumnName()) + " > " + max
                        + " THEN 1 ELSE 0 END) AS _bad FROM " + table + where);
            }
            case DatQualityRule.TYPE_ENUM -> {
                yield ("SELECT COUNT(*) AS _total, SUM(CASE WHEN " + col(rule.getColumnName())
                        + " NOT IN (" + inList(config.get("values")) + ") THEN 1 ELSE 0 END) AS _bad FROM "
                        + table + where);
            }
            case DatQualityRule.TYPE_CUSTOM_SQL -> {
                String userSql = str(config.get("sql"));
                if (userSql == null || userSql.isBlank()) {
                    throw new ServiceException(400, "custom_sql 规则必须在规则配置中提供 sql");
                }
                yield "SELECT COUNT(*) AS _bad FROM ("
                        + ApiSqlGuard.guard(userSql, internal, tenantId, java.util.Set.of(), false).sql() + ") _mse_q";
            }
            case DatQualityRule.TYPE_FLUCTUATION -> "SELECT COUNT(*) AS _total FROM " + table + where;
            default -> throw new ServiceException(400, "未知规则类型：" + rule.getRuleType());
        };

        Long total = null;
        Long bad = null;
        try (Connection conn = openConnection(rule.getDatasourceId(), internal);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = getObjectAsLong(rs, "_total");
                    bad = getObjectAsLong(rs, "_bad");
                }
            }
        }

        report.setTotalRows(total);
        report.setBadRows(bad);
        if (DatQualityRule.TYPE_FLUCTUATION.equals(rule.getRuleType())) {
            // 波动检测：对比上一份成功报告的总行数
            BigDecimal threshold = new BigDecimal(strOr(config.get("thresholdPct"), "10"));
            DatQualityReport last = reportMapper.selectOne(new LambdaQueryWrapper<DatQualityReport>()
                    .eq(DatQualityReport::getRuleId, rule.getId())
                    .eq(DatQualityReport::getStatus, DatQualityReport.STATUS_SUCCESS)
                    .orderByDesc(DatQualityReport::getId)
                    .last("LIMIT 1"));
            if (last == null || last.getTotalRows() == null) {
                // 无基线：本次仅建立基线，视为通过
                report.setBadRows(0L);
                report.setPassRate(BigDecimal.valueOf(100));
                return;
            }
            long baseline = last.getTotalRows();
            long delta = Math.abs(total - baseline);
            double pct = baseline == 0 ? (total == 0 ? 0 : Double.MAX_VALUE)
                    : delta * 100.0 / baseline;
            report.setBadRows(delta);
            report.setPassRate(BigDecimal.valueOf(100 - Math.min(pct, 100))
                    .setScale(2, RoundingMode.HALF_UP));
            if (new BigDecimal(String.valueOf(pct)).compareTo(threshold) > 0) {
                report.setStatus(DatQualityReport.STATUS_FAILED);
                report.setErrorMsg("行数波动 " + String.format(Locale.ROOT, "%.2f", pct)
                        + "% 超过阈值 " + threshold + "%（基线 " + baseline + " → 当前 " + total + "）");
            }
            return;
        }

        if (total != null && total > 0 && bad != null) {
            report.setPassRate(BigDecimal.valueOf(total - bad).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
        } else {
            report.setPassRate(BigDecimal.valueOf(100));
        }
        if (bad != null && bad > 0) {
            report.setStatus(DatQualityReport.STATUS_FAILED);
        }
    }

    // ---------------- 报告查询 ----------------

    public PageResult<DatQualityReport> reports(long pageNum, long pageSize, Long ruleId, String status) {
        LambdaQueryWrapper<DatQualityReport> wrapper = new LambdaQueryWrapper<DatQualityReport>()
                .eq(ruleId != null, DatQualityReport::getRuleId, ruleId)
                .eq(status != null && !status.isBlank(), DatQualityReport::getStatus, status)
                .orderByDesc(DatQualityReport::getId);
        Page<DatQualityReport> page = reportMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    // ---------------- 内部工具 ----------------

    private Connection openConnection(Long datasourceId, boolean internal) throws SQLException {
        if (internal) {
            return masterConnections.open(QualityService.class);
        }
        DatDatasource ds = datasourceMapper.selectById(datasourceId);
        if (ds == null) {
            throw new ServiceException(404, "数据源不存在或已被删除");
        }
        if (ds.getStatus() != DatDatasource.STATUS_ENABLED) {
            throw new ServiceException(400, "数据源已停用");
        }
        return connectorManager.borrow(ds);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, Map.class);
        } catch (Exception e) {
            throw new ServiceException(400, "规则配置 JSON 非法：" + e.getMessage());
        }
    }

    /** 列名标识符校验（防注入：列名直接拼 SQL） */
    private String col(String name) {
        if (name == null || !name.matches(IDENTIFIER)) {
            throw new ServiceException(400, "检测列名非法：" + name);
        }
        return name;
    }

    /** 枚举值列表：转义单引号后拼 IN 清单 */
    private String inList(Object values) {
        if (!(values instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(400, "enum 规则必须在规则配置中提供非空 values 数组");
        }
        return list.stream().map(v -> "'" + String.valueOf(v).replace("'", "''") + "'")
                .reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    private BigDecimal numeric(Object value, String field) {
        if (value == null) {
            throw new ServiceException(400, "range 规则必须提供 " + field);
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ServiceException(400, field + " 必须为数值");
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String strOr(Object value, String def) {
        String s = str(value);
        return s == null || s.isBlank() ? def : s;
    }

    private Long getObjectAsLong(ResultSet rs, String label) throws SQLException {
        Object v = rs.getObject(label);
        if (v == null) {
            return null;
        }
        if (v instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(v));
    }

    private void validate(DatQualityRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new ServiceException(400, "规则名称不能为空");
        }
        if (rule.getDatasourceId() == null) {
            rule.setDatasourceId(0L);
        }
        String table = rule.getTargetTable();
        if (table == null || !table.matches(IDENTIFIER)) {
            throw new ServiceException(400, "目标表名非法");
        }
        boolean internal = rule.getDatasourceId() == 0;
        if (internal && !table.toLowerCase(Locale.ROOT).startsWith("ods_")) {
            throw new ServiceException(400, "中台库质量检测仅允许 ods_ 前缀贴源表");
        }
        String type = rule.getRuleType();
        boolean needColumn = DatQualityRule.TYPE_NOT_NULL.equals(type)
                || DatQualityRule.TYPE_UNIQUE.equals(type)
                || DatQualityRule.TYPE_RANGE.equals(type)
                || DatQualityRule.TYPE_ENUM.equals(type);
        if (needColumn) {
            col(rule.getColumnName());
        } else if (!DatQualityRule.TYPE_CUSTOM_SQL.equals(type)
                && !DatQualityRule.TYPE_FLUCTUATION.equals(type)) {
            throw new ServiceException(400, "未知规则类型：" + type);
        }
        if (DatQualityRule.TYPE_CUSTOM_SQL.equals(type)) {
            Map<String, Object> config = parseConfig(rule.getRuleConfig());
            String sql = str(config.get("sql"));
            if (sql == null || sql.isBlank()) {
                throw new ServiceException(400, "custom_sql 规则必须提供 sql");
            }
            ApiSqlGuard.guard(sql, internal, TenantContext.getRequiredTenantId(), java.util.Set.of(), false);
        }
        // 保存期配置校验：range 缺 min/max、enum 缺非空 values 此前延迟到执行期才报错，
        // 与 columnName 的保存期校验口径不一致（2026-10-03 用例 DATA-QC-002 实测）
        if (DatQualityRule.TYPE_RANGE.equals(type)) {
            Map<String, Object> config = parseConfig(rule.getRuleConfig());
            numeric(config.get("min"), "min");
            numeric(config.get("max"), "max");
        }
        if (DatQualityRule.TYPE_ENUM.equals(type)) {
            Map<String, Object> config = parseConfig(rule.getRuleConfig());
            inList(config.get("values"));
        }
        if (rule.getCron() != null && !rule.getCron().isBlank()) {
            try {
                org.springframework.scheduling.support.CronExpression.parse(rule.getCron().trim());
            } catch (IllegalArgumentException e) {
                throw new ServiceException(400, "cron 表达式非法：" + e.getMessage());
            }
            rule.setCron(rule.getCron().trim());
        } else {
            rule.setCron(null);
        }
        if (rule.getStatus() == null) {
            rule.setStatus(DatQualityRule.STATUS_ENABLED);
        }
    }

    private String truncate(String message) {
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
