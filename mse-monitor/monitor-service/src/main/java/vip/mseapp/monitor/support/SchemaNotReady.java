package vip.mseapp.monitor.support;

import java.sql.SQLException;

/**
 * 「库未就绪」判定：独立库租户自注册进路由表到初始化直建之间存在两级开放窗口
 * （调度器 push+runIgnore 直连不经过 TenantDsLazyInitializer，建库与建表先后到达）——
 * ①物理库尚未创建（sys_tenant_datasource 注册可见早于建库，MySQL 报 Unknown database）；
 * ②库已建但 mon_* 表尚未直建。窗口期内两者均属预期中间态，调度类组件应安静跳过、
 * 初始化完成后自动纳入。
 *
 * <p>缺表口径与框架 LocalTenantDsMetadataProvider.logReadFailure 一致
 * （SQLState 42S02/42P01 或消息含 doesn't exist 等）；缺库口径为 SQLState 3D000
 * （PG 系 invalid_catalog_name）或消息含 Unknown database（MySQL Connector/J 对 1049
 * 报 SQLState 42000，只能按消息识别）。两者均沿 cause 链下钻——调度侧拿到的是
 * CannotGetJdbcConnectionException 等 Spring 翻译包装，原始 SQLException 在 cause 中。
 */
public final class SchemaNotReady {

    private SchemaNotReady() {
    }

    /** true=物理库不存在（注册早于建库的预期态），false=其他异常（权限等真错误） */
    public static boolean isDatabaseMissing(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se) {
                String state = se.getSQLState();
                String message = se.getMessage() == null ? "" : se.getMessage();
                if ("3D000".equals(state) || message.contains("Unknown database")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** true=缺表（库已建但尚未懒初始化直建的预期态），false=其他异常（连接、权限等真错误） */
    public static boolean isTableMissing(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se) {
                String state = se.getSQLState();
                String message = se.getMessage() == null ? "" : se.getMessage();
                if ("42S02".equals(state) || "42P01".equals(state)
                        || message.contains("doesn't exist") || message.contains("does not exist")
                        || message.contains("不存在") || message.contains("not found")) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
