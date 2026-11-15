package vip.mseapp.ai.api.support;

import org.junit.jupiter.api.Test;
import vip.mseapp.core.exception.ServiceException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 防护器回归：业务库档（前缀黑白名单 + 执行期谓词注入）与中台库/外部档
 * （ods_/白名单 + 静态租户注入）行为基线。
 */
class ApiSqlGuardBizTest {

    private static final Set<String> DENY_PREFIX = Set.of("sys_", "ai_", "dat_", "open_", "ods_", "undo_");
    private static final Set<String> DENY_TABLE = Set.of("t_secret");
    private static final Set<String> ALLOW_PREFIX = Set.of("mes_", "oa_", "mp_");

    // ---------------- guardBiz：表访问控制 ----------------

    @Test
    void bizDenyPrefixRejected() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM sys_user", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM ai_chat_message", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM dat_datasource", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
    }

    @Test
    void bizDenyExactTableRejected() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM t_secret", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
    }

    @Test
    void bizJoinTableAlsoChecked() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM mes_order o JOIN sys_user u ON o.create_by = u.id",
                        DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
        assertDoesNotThrow(() ->
                ApiSqlGuard.guardBiz("SELECT * FROM mes_order o JOIN oa_form_data f ON o.id = f.id",
                        DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
    }

    @Test
    void bizUnmatchedPrefixRejected() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM foo_table", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
    }

    @Test
    void bizAllowPrefixPassesWithoutStaticInjection() {
        ApiSqlGuard.GuardedSql guarded = ApiSqlGuard.guardBiz(
                "SELECT * FROM mes_order WHERE qty > 5", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX);
        assertTrue(guarded.tables().contains("mes_order"));
        // 业务库档不做静态谓词注入（执行期探测列后注入）
        assertFalse(guarded.sql().contains("tenant_id"));
        assertFalse(guarded.sql().contains("deleted"));
    }

    @Test
    void bizSchemaQualifiedAndQuotedRejected() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM mse_tenant.mes_order", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
        // schema 指向他租户独立库（列探测与租户注入在目标库上失效，必须拒绝）
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM mse_tenant2.mes_order", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM `mes_order`", DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
    }

    @Test
    void legacySchemaQualifiedRejected() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guard("SELECT * FROM mse_tenant2.ods_mes_order", true, 5L, Set.of(), true));
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guard("SELECT * FROM mse_tenant2.oa_form_data", true, 5L, Set.of("oa_form_data"), true));
    }

    @Test
    void bizMultiStatementAndSubqueryRejected() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM mes_order; SELECT * FROM sys_user",
                        DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM (SELECT * FROM mes_order) t",
                        DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guardBiz("SELECT * FROM mes_order UNION SELECT * FROM mes_report",
                        DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX));
    }

    // ---------------- injectBizPredicates：执行期口径注入 ----------------

    @Test
    void injectTenantAndDeleted() {
        String guarded = guardBizSql("SELECT * FROM mes_order WHERE qty > 5");
        String sql = ApiSqlGuard.injectBizPredicates(guarded, 2L,
                Set.of("mes_order"), Set.of("mes_order"));
        assertTrue(sql.contains("mes_order.tenant_id = 2"));
        assertTrue(sql.contains("mes_order.deleted = 0"));
        assertTrue(sql.contains("qty > 5"));
    }

    @Test
    void injectTenantOnly() {
        String guarded = guardBizSql("SELECT * FROM mes_report");
        String sql = ApiSqlGuard.injectBizPredicates(guarded, 3L, Set.of("mes_report"), Set.of());
        assertTrue(sql.contains("mes_report.tenant_id = 3"));
        assertFalse(sql.contains("deleted"));
    }

    @Test
    void injectWithAliasAndJoin() {
        String guarded = guardBizSql(
                "SELECT o.id FROM mes_order o JOIN mes_report r ON o.id = r.order_id WHERE o.status = 1");
        String sql = ApiSqlGuard.injectBizPredicates(guarded, 7L,
                Set.of("mes_order", "mes_report"), Set.of("mes_report"));
        // 别名限定 + 各表按自己命中注入
        assertTrue(sql.contains("o.tenant_id = 7"));
        assertTrue(sql.contains("r.tenant_id = 7"));
        assertTrue(sql.contains("r.deleted = 0"));
        assertFalse(sql.contains("o.deleted"));
    }

    @Test
    void injectNothingWhenNoColumns() {
        String guarded = guardBizSql("SELECT * FROM mes_order WHERE id = 1");
        String sql = ApiSqlGuard.injectBizPredicates(guarded, 2L, Set.of(), Set.of());
        assertTrue(sql.contains("id = 1"));
        assertFalse(sql.contains("tenant_id"));
    }

    // ---------------- 既有档回归：guard() 行为不变 ----------------

    @Test
    void legacyOdsAndWhitelistInjectTenantStatically() {
        ApiSqlGuard.GuardedSql guarded = ApiSqlGuard.guard(
                "SELECT * FROM ods_mes_order", true, 5L, Set.of("oa_form_data"), true);
        assertTrue(guarded.sql().contains("tenant_id = 5"));

        guarded = ApiSqlGuard.guard("SELECT * FROM oa_form_data", true, 5L, Set.of("oa_form_data"), true);
        assertTrue(guarded.sql().contains("tenant_id = 5"));
    }

    @Test
    void legacyNonWhitelistedRejected() {
        assertThrows(ServiceException.class, () ->
                ApiSqlGuard.guard("SELECT * FROM mes_order", true, 5L, Set.of(), true));
    }

    @Test
    void legacyExternalNoInjection() {
        ApiSqlGuard.GuardedSql guarded = ApiSqlGuard.guard(
                "SELECT * FROM any_table", false, 5L, Set.of(), true);
        assertFalse(guarded.sql().contains("tenant_id"));
    }

    @Test
    void legacyNamedParamRewritten() {
        ApiSqlGuard.GuardedSql guarded = ApiSqlGuard.guard(
                "SELECT * FROM ods_mes_order WHERE id = :id", true, 5L, Set.of(), true);
        assertTrue(guarded.sql().contains("?"));
        assertTrue(guarded.paramNames().contains("id"));
    }

    @Test
    void applyLimitAppendsPagination() {
        String guarded = guardBizSql("SELECT * FROM mes_order");
        String sql = ApiSqlGuard.applyLimit(guarded, 20, 201);
        assertTrue(sql.contains("LIMIT 201"));
        assertTrue(sql.contains("OFFSET 20"));
    }

    // ---------------- 配置判定：AiBizQueryProperties ----------------

    @Test
    void configDenyDefaultsAndNormalization() {
        AiBizQueryProperties cfg = new AiBizQueryProperties();
        // 默认清单：系统/凭证/贴源/seata 回滚表拒绝，业务表放行（是否放行由模块前缀白名单判定）
        assertTrue(cfg.isDenied("sys_user"));
        assertTrue(cfg.isDenied("undo_log"));
        assertTrue(cfg.isDenied("ods_mes_order"));
        assertFalse(cfg.isDenied("oa_task_log"));
        // 精确散表拒绝 + 归一化（大写/空白）
        cfg.setDenyTables(java.util.List.of(" T_Secret "));
        assertTrue(cfg.isDenied("t_secret"));
    }

    private String guardBizSql(String sql) {
        return ApiSqlGuard.guardBiz(sql, DENY_PREFIX, DENY_TABLE, ALLOW_PREFIX).sql();
    }
}
