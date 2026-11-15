package vip.mseapp.ai.api.support;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import vip.mseapp.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受控只读 SQL 防护器（jsqlparser，原 data-service 同名类上收为 ai-api 共享件）：
 *
 * <ul>
 *   <li>仅允许单条 SELECT（拒绝 UNION / WITH / 子查询派生表 / FOR UPDATE 等锁语句）；</li>
 *   <li>:param 占位提取并改写为 JDBC ?，参数化绑定杜绝注入；</li>
 *   <li>中台库模式（internal，data-service 数据服务/同步通道）分两档：
 *     数据服务档：允许 ods_ 前缀贴源表与租户白名单业务表（allowedTables），两类表均注入
 *     tenant_id 谓词（跨租户行级隔离；白名单表缺租户列由执行期在目标库上探测拦截）；
 *     同步来源档：允许引用本库业务表，ods_ 贴源表仍静态注入 tenant_id 谓词，
 *     业务表是否有租户列由执行期探测校验；</li>
 *   <li>业务库模式（{@link #guardBiz}，模块 biz 查询工具通道）：前缀白名单放行 + 硬黑名单兜底；
 *     不做静态谓词注入——业务表存在无 tenant_id 的全局表，由执行器在目标库上逐表探测列后
 *     经 {@link #injectBizPredicates} 动态注入租户谓词与 deleted=0 口径；</li>
 *   <li>外部数据源模式：仅校验 SELECT 语法，不注入租户条件（数据源本身归属租户，数据源级隔离）。</li>
 * </ul>
 *
 * <p>约束：:param 占位不得出现在字符串字面量内（不做字面量感知替换）。
 */
public final class ApiSqlGuard {

    /** :name 占位（排除 PG :: 强转） */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

    private static final List<String> FORBIDDEN = List.of(
            "for update", "lock in share mode", "into outfile", "into dumpfile");

    private ApiSqlGuard() {
    }

    /**
     * 校验并改写 SQL（数据服务档）。
     *
     * @param sqlTemplate   用户 SQL 模板
     * @param internal      true=中台库贴源层（限 ods_ 表与白名单业务表并注入租户条件）；false=外部数据源
     * @param tenantId      租户（internal 时注入用）
     * @param allowedTables 中台库白名单业务表（小写归一化；仅 internal 档消费，空集=仅放行 ods_ 前缀表）
     * @param allowParams   false 时不允许 :param 占位
     */
    public static GuardedSql guard(String sqlTemplate, boolean internal, Long tenantId,
                                   Set<String> allowedTables, boolean allowParams) {
        return doGuard(sqlTemplate, internal, false, tenantId, allowedTables, allowParams);
    }

    /**
     * 校验并改写同步任务来源 SQL（固定不允许 :param 占位）。
     *
     * @param internal true=内置中台库档：允许引用本库业务表，ods_ 贴源表仍注入租户条件
     *                 （业务表是否含租户列由执行期探测校验）；false=外部数据源档：仅校验 SELECT 语法
     */
    public static GuardedSql guardSyncSource(String sqlTemplate, boolean internal, Long tenantId) {
        return doGuard(sqlTemplate, internal, true, tenantId, Set.of(), false);
    }

    /**
     * 校验并改写业务库档 SQL（模块 biz 查询工具通道：text2sql 直查本模块业务表）。
     *
     * <p>表判定三段式：命中硬黑名单（deny 前缀或精确表名）→ 拒绝；命中业务前缀白名单 → 放行；
     * 其余 → 拒绝（默认不透明）。所有入参集合须为小写归一化（见
     * {@code AiBizQueryProperties} 的归一化取值方法）。
     *
     * @param sqlTemplate   模型生成的 SQL（单条 SELECT，分页由执行器追加）
     * @param denyPrefixes  硬拒绝表前缀（安全底线，优先于 allow 判定，配置不可越过）
     * @param denyTables    硬拒绝精确表名
     * @param allowPrefixes 放行业务表前缀
     */
    public static GuardedSql guardBiz(String sqlTemplate, Set<String> denyPrefixes,
                                      Set<String> denyTables, Set<String> allowPrefixes) {
        Prepared prepared = prepare(sqlTemplate, true);
        for (Table table : prepared.tables()) {
            requireBareTableName(table);
            String lowerName = table.getName().toLowerCase(Locale.ROOT);
            if (denyTables.contains(lowerName) || denyPrefixes.stream().anyMatch(lowerName::startsWith)) {
                throw new ServiceException(400, "业务库查询禁止访问系统/凭证等敏感表，非法表：" + table.getName());
            }
            if (allowPrefixes.stream().noneMatch(lowerName::startsWith)) {
                throw new ServiceException(400, "业务库查询仅允许业务前缀表（"
                        + String.join("、", allowPrefixes) + "），非法表：" + table.getName());
            }
        }
        return toGuarded(prepared);
    }

    /**
     * 业务库档执行期谓词注入（探测列之后调用）：tenantTables 中的表注入 {@code alias.tenant_id = ?}
     * （字面量绑定可信租户 ID），notDeletedTables 中的表注入 {@code alias.deleted = 0}
     * （逻辑删除口径，防止 text2sql 统计算进已删除行）。
     *
     * @param sql              guardBiz 改写后的 SQL
     * @param tenantId         当前租户（可信上下文取值）
     * @param tenantTables     目标库上含 tenant_id 列的表名（小写）
     * @param notDeletedTables 目标库上含 deleted 列的表名（小写）
     */
    public static String injectBizPredicates(String sql, Long tenantId,
                                             Set<String> tenantTables, Set<String> notDeletedTables) {
        PlainSelect plain = parseSelect(sql);
        injectPredicates(plain, collectTables(plain), tenantId,
                tenantTables::contains, notDeletedTables::contains);
        return plain.toString();
    }

    private static GuardedSql doGuard(String sqlTemplate, boolean internal, boolean syncSource,
                                      Long tenantId, Set<String> allowedTables, boolean allowParams) {
        Prepared prepared = prepare(sqlTemplate, allowParams);
        if (internal) {
            // 同步来源档放开业务表；数据服务档放行 ods_ 贴源表 + 白名单业务表，
            // 两类表均静态注入租户条件（白名单表缺租户列由执行期在目标库上探测拦截）
            Set<String> allowed = normalize(allowedTables);
            List<Table> injectTables = new ArrayList<>();
            for (Table table : prepared.tables()) {
                requireBareTableName(table);
                String lowerName = table.getName().toLowerCase(Locale.ROOT);
                boolean ods = lowerName.startsWith("ods_");
                boolean whitelisted = allowed.contains(lowerName);
                if (!ods && !whitelisted && !syncSource) {
                    String tip = allowed.isEmpty() ? "当前未配置白名单"
                            : "白名单：" + String.join("、", allowed);
                    throw new ServiceException(400, "中台库数据服务仅允许查询 ods_ 前缀贴源表或白名单业务表"
                            + "（" + tip + "），非法表：" + table.getName());
                }
                if (ods || whitelisted) {
                    injectTables.add(table);
                }
            }
            injectPredicates(prepared.plain(), injectTables, tenantId, t -> true, t -> false);
        }
        return toGuarded(prepared);
    }

    /** 公共解析前置：空白/多语句/禁用关键字/:param 改写/单条 SELECT/实体表收集 */
    private static Prepared prepare(String sqlTemplate, boolean allowParams) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new ServiceException(400, "SQL 不能为空");
        }
        String sql = sqlTemplate.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1);
        }
        if (sql.contains(";")) {
            throw new ServiceException(400, "仅允许单条 SELECT 语句");
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        for (String phrase : FORBIDDEN) {
            if (lower.contains(phrase)) {
                throw new ServiceException(400, "SQL 含被禁止的关键字：" + phrase);
            }
        }

        // :param → ? 提取
        List<String> paramNames = new ArrayList<>();
        if (NAMED_PARAM.matcher(sql).find() && !allowParams) {
            throw new ServiceException(400, "该场景不支持 :param 参数占位");
        }
        Matcher matcher = NAMED_PARAM.matcher(sql);
        StringBuilder rewritten = new StringBuilder();
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
            matcher.appendReplacement(rewritten, "?");
        }
        matcher.appendTail(rewritten);
        String jdbcSql = rewritten.toString();

        PlainSelect plain = parseSelect(jdbcSql);
        return new Prepared(plain, collectTables(plain), List.copyOf(paramNames));
    }

    private static GuardedSql toGuarded(Prepared prepared) {
        return new GuardedSql(prepared.plain().toString(), prepared.paramNames(),
                prepared.tables().stream().map(Table::getName).toList());
    }

    /** 禁止 schema 限定名与引号/中括号包装（仅允许直接引用本库表名；schema 限定可把执行
     * 路由到同实例其他库——如他租户独立库，列探测与租户注入均在其上失效，必须拒绝） */
    private static void requireBareTableName(Table table) {
        String name = table.getName();
        if (table.getSchemaName() != null || name.contains(".") || name.contains("`") || name.contains("\"")
                || name.contains("'") || name.contains("[")) {
            throw new ServiceException(400, "仅允许直接引用本库表名，非法表：" + table.getFullyQualifiedName());
        }
    }

    /** 白名单归一化：trim + 小写（防护器统一按小写比对，调用方传入大小写不敏感） */
    private static Set<String> normalize(Set<String> allowedTables) {
        if (allowedTables == null || allowedTables.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String table : allowedTables) {
            if (table != null && !table.isBlank()) {
                normalized.add(table.trim().toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    /** 追加分页 LIMIT/OFFSET（pageSize 已由调用方按 maxRows 截顶） */
    public static String applyLimit(String sql, long offset, long rowCount) {
        PlainSelect plain = parseSelect(sql);
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(rowCount));
        plain.setLimit(limit);
        if (offset > 0) {
            Offset offsetExpr = new Offset();
            offsetExpr.setOffset(new LongValue(offset));
            plain.setOffset(offsetExpr);
        }
        return plain.toString();
    }

    private static PlainSelect parseSelect(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new ServiceException(400, "SQL 解析失败：" + e.getMessage());
        }
        if (!(statement instanceof Select select) || !(select.getSelectBody() instanceof PlainSelect plain)) {
            throw new ServiceException(400, "仅允许单条 SELECT 语句");
        }
        return plain;
    }

    /** 主表与全部 JOIN 表都必须是实体表（拒绝派生表/子查询） */
    private static List<Table> collectTables(PlainSelect plain) {
        List<Table> tables = new ArrayList<>();
        if (!(plain.getFromItem() instanceof Table from)) {
            throw new ServiceException(400, "不支持 FROM 子查询/派生表");
        }
        tables.add(from);
        List<Join> joins = plain.getJoins();
        if (joins != null) {
            for (Join join : joins) {
                if (!(join.getRightItem() instanceof Table right)) {
                    throw new ServiceException(400, "不支持 JOIN 子查询/派生表");
                }
                tables.add(right);
            }
        }
        return tables;
    }

    /**
     * 为命中过滤条件的表注入谓词（alias 限定，值为字面量绑定）：
     * tenantTables 命中（按表名小写）→ {@code alias.tenant_id = 租户 ID}；
     * notDeletedTables 命中 → {@code alias.deleted = 0}。
     */
    private static void injectPredicates(PlainSelect plain, List<Table> tables, Long tenantId,
                                         Predicate<String> tenantTables, Predicate<String> notDeletedTables) {
        Expression combined = null;
        for (Table table : tables) {
            String qualifier = table.getAlias() != null ? table.getAlias().getName() : table.getName();
            String lowerName = table.getName().toLowerCase(Locale.ROOT);
            if (tenantTables.test(lowerName)) {
                combined = appendPredicate(combined,
                        new EqualsTo(new Column(qualifier + ".tenant_id"), new LongValue(tenantId)));
            }
            if (notDeletedTables.test(lowerName)) {
                combined = appendPredicate(combined,
                        new EqualsTo(new Column(qualifier + ".deleted"), new LongValue(0)));
            }
        }
        Expression existing = plain.getWhere();
        plain.setWhere(combined == null ? existing
                : existing == null ? combined : new AndExpression(combined, existing));
    }

    private static Expression appendPredicate(Expression combined, Expression predicate) {
        return combined == null ? predicate : new AndExpression(combined, predicate);
    }

    /** 公共解析结果：改写后的 SELECT 语法树 + 实体表清单 + :param 名称（按出现顺序） */
    private record Prepared(PlainSelect plain, List<Table> tables, List<String> paramNames) {
    }

    /**
     * 防护结果。
     *
     * @param sql        改写后的 JDBC SQL（? 占位）
     * @param paramNames 按出现顺序的 :param 名称（可与 ? 位置一一对应，重名占位重复出现）
     * @param tables     引用表名（FROM/JOIN 主表，不含 schema 限定）
     */
    public record GuardedSql(String sql, List<String> paramNames, List<String> tables) {
    }
}
