package vip.mseapp.data.engine;

import java.sql.SQLException;
import java.util.List;

/**
 * 数据通道写入器：向中台存储写入行。
 *
 * <p>一期为 JDBC 实现（中台库 ODS 贴源表，动态建表）；三期可扩展 ClickHouse/Doris
 * 写入实现（DWS 汇总层），即数据中台 OLAP 分析层的预留接入点。
 */
public interface DataWriter extends AutoCloseable {

    /**
     * 确保目标表存在（不存在则按列定义建表，自动附加 tenant_id 列），并做本次写入前的清理：
     * full 模式清空本租户全部行；incr 模式清除本租户水位之后的行（保证重跑幂等）。
     *
     * 注意：ODS 物理表全租户共享，清理必须带 tenant_id 条件，严禁 TRUNCATE。
     */
    void prepare(List<ColumnMeta> columns, boolean full, String incrColumn, Object watermark)
            throws SQLException;

    /** 批量写入行（行内值顺序与列一致，最后补 tenantId）；返回写入行数 */
    int write(List<Object[]> rows, long tenantId) throws SQLException;

    @Override
    void close();
}
