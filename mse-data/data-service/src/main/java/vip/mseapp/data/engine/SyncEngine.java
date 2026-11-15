package vip.mseapp.data.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.config.DataProperties;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.entity.DatSyncJob;
import vip.mseapp.data.entity.DatSyncJobLog;
import vip.mseapp.data.mapper.DatDatasourceMapper;
import vip.mseapp.data.mapper.DatSyncJobLogMapper;
import vip.mseapp.data.mapper.DatSyncJobMapper;
import vip.mseapp.ai.api.support.ApiSqlGuard;
import vip.mseapp.data.support.DataConnectorManager;
import vip.mseapp.data.support.MasterDbGuard;
import vip.mseapp.ai.api.support.TenantColumnProbe;
import vip.mseapp.mybatis.datasource.TenantRawConnectionRouter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 同步执行器：一次「读取 → 写入 ODS」的完整编排（手动触发与调度共用）。
 *
 * <p>调用方必须已设置租户上下文（HTTP 请求自带；调度侧用 runAs 包裹）。
 * 读取走租户注册的外部数据源，写入走中台库 master 连接（autoCommit=false，逐批提交）。
 * 注意：本方法跨外部库与中台库两个连接，按基座事务规范不使用 @Transactional。
 */
@Component
public class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    /** 目标表命名约束：ods_ 前缀小写 */
    public static final String TARGET_TABLE_PATTERN = "^ods_[a-z0-9_]{1,80}$";
    /** 源表/列名标识符约束 */
    public static final String IDENTIFIER_PATTERN = "^[A-Za-z_][A-Za-z0-9_$]{0,127}$";
    /** 租户列名（共享库形态行级隔离依据） */
    private static final String TENANT_COLUMN = "tenant_id";

    private final DataConnectorManager connectorManager;
    private final TenantRawConnectionRouter masterConnections;
    private final DataProperties properties;
    private final DatDatasourceMapper datasourceMapper;
    private final DatSyncJobMapper jobMapper;
    private final DatSyncJobLogMapper logMapper;
    private final MasterDbGuard masterDbGuard;

    /** 执行中任务（防并发重入，单实例内存语义） */
    private final Set<Long> runningJobs = ConcurrentHashMap.newKeySet();

    public SyncEngine(DataConnectorManager connectorManager, TenantRawConnectionRouter masterConnections,
                      DataProperties properties, DatDatasourceMapper datasourceMapper,
                      DatSyncJobMapper jobMapper, DatSyncJobLogMapper logMapper,
                      MasterDbGuard masterDbGuard) {
        this.connectorManager = connectorManager;
        this.masterConnections = masterConnections;
        this.properties = properties;
        this.datasourceMapper = datasourceMapper;
        this.jobMapper = jobMapper;
        this.logMapper = logMapper;
        this.masterDbGuard = masterDbGuard;
    }

    /** 执行一次同步；返回运行日志（内部已落库 success/failed 终态） */
    public DatSyncJobLog execute(DatSyncJob job, String triggerType) {
        return execute(job, triggerType, java.util.Set.of(job.getId()));
    }

    /**
     * 执行一次同步并在成功后级联触发依赖它的后继任务（链上限 20 防失控）。
     *
     * @param chain 已在本次级联链中的任务编号（防环；调度/手动入口传入自身）
     */
    public DatSyncJobLog execute(DatSyncJob job, String triggerType, java.util.Set<Long> chain) {
        if (!runningJobs.add(job.getId())) {
            throw new ServiceException(400, "任务正在执行中，请勿重复触发");
        }
        long tenantId = job.getTenantId();
        DatSyncJobLog runLog = new DatSyncJobLog();
        boolean success = false;
        try {
            runLog.setTenantId(tenantId);
            runLog.setJobId(job.getId());
            runLog.setTriggerType(triggerType);
            runLog.setStatus(DatSyncJobLog.STATUS_RUNNING);
            runLog.setSyncMode(job.getSyncMode());
            runLog.setWatermarkUsed(job.getWatermark());
            runLog.setRowCount(0L);
            runLog.setStartTime(LocalDateTime.now());
            runLog.setCostMs(0L);
            logMapper.insert(runLog);

            doExecute(job, runLog, tenantId);
            runLog.setStatus(DatSyncJobLog.STATUS_SUCCESS);
            success = true;
            return runLog;
        } catch (Exception e) {
            log.warn("同步任务 {} 执行失败", job.getId(), e);
            runLog.setStatus(DatSyncJobLog.STATUS_FAILED);
            runLog.setErrorMsg(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return runLog;
        } finally {
            finishLog(job, runLog);
            runningJobs.remove(job.getId());
        }
    }

    /** 执行 + 成功后级联触发后继任务（依赖编排） */
    public DatSyncJobLog executeAndCascade(DatSyncJob job, String triggerType, java.util.Set<Long> chain) {
        DatSyncJobLog runLog = execute(job, triggerType, chain);
        if (!DatSyncJobLog.STATUS_SUCCESS.equals(runLog.getStatus()) || chain.size() >= 20) {
            return runLog;
        }
        List<DatSyncJob> dependents = jobMapper.selectList(
                new LambdaQueryWrapper<DatSyncJob>().eq(DatSyncJob::getDepJobId, job.getId()));
        for (DatSyncJob dependent : dependents) {
            if (dependent.getStatus() != DatSyncJob.STATUS_ENABLED || chain.contains(dependent.getId())) {
                continue;
            }
            java.util.Set<Long> nextChain = new java.util.HashSet<>(chain);
            nextChain.add(dependent.getId());
            log.info("级联触发同步任务 {}（前置 {} 成功）", dependent.getId(), job.getId());
            executeAndCascade(dependent, triggerType, nextChain);
        }
        return runLog;
    }

    private void doExecute(DatSyncJob job, DatSyncJobLog runLog, long tenantId) throws Exception {
        DatDatasource datasource = null;
        if (job.getDatasourceId() != null && job.getDatasourceId() > 0) {
            datasource = datasourceMapper.selectById(job.getDatasourceId());
            if (datasource == null) {
                throw new ServiceException(404, "来源数据源不存在或已被删除");
            }
            if (datasource.getStatus() != DatDatasource.STATUS_ENABLED) {
                throw new ServiceException(400, "来源数据源已停用");
            }
            // 执行期兜底：覆盖登记护栏上线前的存量数据源（指向平台主库的一律拒绝）
            masterDbGuard.assertNotMaster(datasource);
        }
        boolean incr = DatSyncJob.MODE_INCR.equals(job.getSyncMode());
        Object watermark = incr ? JdbcReader.parseWatermark(job.getWatermark()) : null;

        ApiSqlGuard.GuardedSql guarded = buildCoreQuery(job);
        boolean internal = datasource == null;
        String coreQuery = guarded.sql();
        try (Connection sourceConn = datasource != null
                ? connectorManager.borrow(datasource) : masterConnections.open(SyncEngine.class)) {
            if (internal) {
                // 引用的业务表含租户列时，结果集必须带出 tenant_id（防投影掉租户列绕过自动过滤）
                ensureTenantColumnProjected(sourceConn, guarded.tables(), coreQuery);
            }
            // 源表若自带 tenant_id 列（共享库形态），自动只取本租户行，避免跨租户串数据
            if (probeTenantColumn(sourceConn, coreQuery)) {
                coreQuery = "SELECT * FROM (" + coreQuery + ") _mse_tenant_src WHERE tenant_id = " + tenantId;
            }
            try (JdbcReader reader = new JdbcReader(sourceConn, coreQuery, incr ? job.getIncrColumn() : null,
                    watermark, properties.getSync().getBatchSize());
                 Connection masterConn = masterConnections.open(SyncEngine.class);
                 JdbcWriter writer = new JdbcWriter(masterConn, job.getTargetTable())) {

                masterConn.setAutoCommit(false);
                writer.prepare(reader.columnMeta(), !incr, job.getIncrColumn(), watermark);
                writer.clearTenantRows(tenantId, incr ? job.getIncrColumn() : null, watermark);

                long rows = 0;
                while (!reader.finished()) {
                    List<Object[]> batch = reader.nextBatch();
                    if (batch.isEmpty()) {
                        break;
                    }
                    rows += writer.write(batch, tenantId);
                }
                runLog.setRowCount(rows);
                if (incr) {
                    runLog.setWatermarkNew(reader.lastWatermarkValue() != null
                            ? reader.lastWatermarkValue() : job.getWatermark());
                }

                job.setRowCount(rows);
                job.setLastRunTime(LocalDateTime.now());
                if (incr && reader.lastWatermarkValue() != null) {
                    job.setWatermark(reader.lastWatermarkValue());
                }
                jobMapper.updateById(job);
            }
        }
    }

    /** 探测源查询结果是否自带 tenant_id 列（共享库表识别） */
    private boolean probeTenantColumn(Connection sourceConn, String coreQuery) throws Exception {
        try (PreparedStatement ps = sourceConn.prepareStatement(
                "SELECT * FROM (" + coreQuery + ") _mse_probe WHERE 1 = 0");
             ResultSet rs = ps.executeQuery()) {
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                if (TENANT_COLUMN.equalsIgnoreCase(meta.getColumnLabel(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 内置中台库来源：引用的业务表含租户列时，结果集必须带出该列以便自动隔离（贴源表已静态注入，无需检查） */
    private void ensureTenantColumnProjected(Connection sourceConn, List<String> tables, String coreQuery)
            throws Exception {
        for (String table : tables) {
            if (table.toLowerCase(Locale.ROOT).startsWith("ods_")) {
                continue;
            }
            if (hasTenantColumn(sourceConn, table) && !probeTenantColumn(sourceConn, coreQuery)) {
                throw new ServiceException(400, "表 " + table + " 含租户列 tenant_id，"
                        + "查询结果必须带出 tenant_id 列以便自动隔离（建议 SELECT t.* 或显式包含 tenant_id）");
            }
        }
    }

    /** 探测表是否含 tenant_id 列（委托 {@link TenantColumnProbe}，兼容大小写敏感库） */
    private boolean hasTenantColumn(Connection sourceConn, String table) throws SQLException {
        return TenantColumnProbe.hasTenantColumn(sourceConn, table);
    }

    private ApiSqlGuard.GuardedSql buildCoreQuery(DatSyncJob job) {
        if (DatSyncJob.SOURCE_SQL.equals(job.getSourceType())) {
            // 自定义查询：中台库来源按同步来源档防护（允许业务表，租户列执行期校验），外部来源仅校验 SELECT
            boolean internal = job.getDatasourceId() == null || job.getDatasourceId() == 0;
            Long tenantId = vip.mseapp.core.context.TenantContext.getRequiredTenantId();
            return ApiSqlGuard.guardSyncSource(job.getQuerySql(), internal, tenantId);
        }
        if (job.getSourceTable() == null || !job.getSourceTable().matches(IDENTIFIER_PATTERN)) {
            throw new ServiceException(400, "源表名非法");
        }
        return new ApiSqlGuard.GuardedSql("SELECT * FROM " + job.getSourceTable(),
                List.of(), List.of(job.getSourceTable()));
    }

    private void finishLog(DatSyncJob job, DatSyncJobLog runLog) {
        runLog.setEndTime(LocalDateTime.now());
        if (runLog.getStartTime() != null) {
            runLog.setCostMs(java.time.Duration.between(runLog.getStartTime(), runLog.getEndTime()).toMillis());
        }
        logMapper.updateById(runLog);
    }

    private String truncate(String message) {
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
