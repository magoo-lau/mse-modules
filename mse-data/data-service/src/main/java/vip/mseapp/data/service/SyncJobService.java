package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.data.engine.SyncEngine;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.entity.DatSyncJob;
import vip.mseapp.data.entity.DatSyncJobLog;
import vip.mseapp.data.mapper.DatDatasourceMapper;
import vip.mseapp.data.mapper.DatSyncJobLogMapper;
import vip.mseapp.data.mapper.DatSyncJobMapper;
import vip.mseapp.ai.api.support.ApiSqlGuard;

import java.util.concurrent.ExecutorService;

/**
 * 同步任务管理：CRUD + 校验 + 手动/调度执行入口。
 */
@Service
public class SyncJobService {

    private final DatSyncJobMapper jobMapper;
    private final DatSyncJobLogMapper logMapper;
    private final DatDatasourceMapper datasourceMapper;
    private final SyncEngine engine;
    private final ExecutorService syncExecutor;
    private final LineageService lineageService;

    public SyncJobService(DatSyncJobMapper jobMapper, DatSyncJobLogMapper logMapper,
                          DatDatasourceMapper datasourceMapper, SyncEngine engine,
                          ExecutorService syncExecutor, LineageService lineageService) {
        this.jobMapper = jobMapper;
        this.logMapper = logMapper;
        this.datasourceMapper = datasourceMapper;
        this.engine = engine;
        this.syncExecutor = syncExecutor;
        this.lineageService = lineageService;
    }

    public PageResult<DatSyncJob> page(long pageNum, long pageSize, String keyword, Integer status) {
        LambdaQueryWrapper<DatSyncJob> wrapper = new LambdaQueryWrapper<DatSyncJob>()
                .like(keyword != null && !keyword.isBlank(), DatSyncJob::getName, keyword)
                .eq(status != null, DatSyncJob::getStatus, status)
                .orderByDesc(DatSyncJob::getId);
        Page<DatSyncJob> page = jobMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    public DatSyncJob get(Long id) {
        DatSyncJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new ServiceException(404, "任务不存在");
        }
        return job;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(DatSyncJob job) {
        validate(job, true);
        jobMapper.insert(job);
        lineageService.refresh(job);
        return job.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(DatSyncJob job) {
        if (job.getId() == null || get(job.getId()) == null) {
            throw new ServiceException(404, "任务不存在");
        }
        validate(job, false);
        // depJobId=0 约定为「清空前置任务」（updateById 忽略 null 字段，需显式置空）
        boolean clearDep = Long.valueOf(0L).equals(job.getDepJobId());
        if (clearDep) {
            job.setDepJobId(null);
            jobMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<DatSyncJob>lambdaUpdate()
                    .eq(DatSyncJob::getId, job.getId())
                    .set(DatSyncJob::getDepJobId, null));
        }
        jobMapper.updateById(job);
        lineageService.refresh(job);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, boolean enable) {
        DatSyncJob job = get(id);
        job.setStatus(enable ? DatSyncJob.STATUS_ENABLED : DatSyncJob.STATUS_DISABLED);
        jobMapper.updateById(job);
    }

    /** 删除任务（运行日志保留供审计） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        DatSyncJob job = get(id);
        // 依赖本任务的后继任务解除依赖
        jobMapper.selectList(new LambdaQueryWrapper<DatSyncJob>()
                        .eq(DatSyncJob::getDepJobId, id))
                .forEach(dep -> {
                    dep.setDepJobId(null);
                    jobMapper.updateById(dep);
                });
        jobMapper.deleteById(id);
        lineageService.remove(id);
    }

    /**
     * 手动执行：异步提交（立即返回，不占 HTTP 线程），前端轮询任务日志看结果。
     * 运行中的重复触发由引擎内存锁拒绝；成功后级联触发依赖它的后继任务。
     */
    public void run(Long id) {
        DatSyncJob job = get(id);
        syncExecutor.submit(() -> TenantContext.runAs(job.getTenantId(), () ->
                engine.executeAndCascade(job, DatSyncJobLog.TRIGGER_MANUAL, java.util.Set.of(job.getId()))));
    }

    /** 调度入口（调度线程无租户上下文，runAs 包裹）；同样支持级联 */
    public void runScheduled(Long jobId) {
        DatSyncJob job = get(jobId);
        if (job.getStatus() != DatSyncJob.STATUS_ENABLED) {
            return;
        }
        TenantContext.runAs(job.getTenantId(), () ->
                engine.executeAndCascade(job, DatSyncJobLog.TRIGGER_SCHED, java.util.Set.of(job.getId())));
    }

    public PageResult<DatSyncJobLog> logs(long pageNum, long pageSize, Long jobId) {
        LambdaQueryWrapper<DatSyncJobLog> wrapper = new LambdaQueryWrapper<DatSyncJobLog>()
                .eq(jobId != null, DatSyncJobLog::getJobId, jobId)
                .orderByDesc(DatSyncJobLog::getId);
        Page<DatSyncJobLog> page = logMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
    }

    private void validate(DatSyncJob job, boolean create) {
        if (job.getName() == null || job.getName().isBlank()) {
            throw new ServiceException(400, "任务名称不能为空");
        }
        if (job.getDatasourceId() == null) {
            throw new ServiceException(400, "来源数据源不能为空");
        }
        boolean tableMode = DatSyncJob.SOURCE_TABLE.equals(job.getSourceType());
        if (job.getDatasourceId() == 0L) {
            // 中台库（内置）来源：贴源层二次加工，或共享库业务表取本租户数据——
            // 行级隔离由执行期保证（ods_ 表静态注入租户条件；业务表探测 tenant_id 列后强制过滤）
        } else {
            DatDatasource ds = datasourceMapper.selectById(job.getDatasourceId());
            if (ds == null) {
                throw new ServiceException(400, "来源数据源不存在");
            }
        }
        if (tableMode) {
            if (job.getSourceTable() == null || !job.getSourceTable().matches(SyncEngine.IDENTIFIER_PATTERN)) {
                throw new ServiceException(400, "源表名非法");
            }
            job.setQuerySql(null);
        } else if (DatSyncJob.SOURCE_SQL.equals(job.getSourceType())) {
            if (job.getQuerySql() == null || job.getQuerySql().isBlank()) {
                throw new ServiceException(400, "自定义查询 SQL 不能为空");
            }
            // 落库前先过一遍语法防护（真实执行在引擎内还会再防护一次）；
            // 中台库来源按同步来源档防护（允许业务表，ods_ 表注入租户条件，业务表租户列执行期校验）
            ApiSqlGuard.guardSyncSource(job.getQuerySql(), job.getDatasourceId() == 0L,
                    TenantContext.getRequiredTenantId());
            job.setSourceTable(null);
        } else {
            throw new ServiceException(400, "来源类型非法");
        }
        if (job.getTargetTable() == null || !job.getTargetTable().matches(SyncEngine.TARGET_TABLE_PATTERN)) {
            throw new ServiceException(400, "目标表必须以 ods_ 开头（小写字母/数字/下划线）");
        }
        boolean incr = DatSyncJob.MODE_INCR.equals(job.getSyncMode());
        if (!incr && !DatSyncJob.MODE_FULL.equals(job.getSyncMode())) {
            throw new ServiceException(400, "同步模式非法");
        }
        if (incr) {
            if (job.getIncrColumn() == null || !job.getIncrColumn().matches(SyncEngine.IDENTIFIER_PATTERN)) {
                throw new ServiceException(400, "增量模式必须提供合法的水位列");
            }
        } else {
            job.setIncrColumn(null);
            job.setWatermark(null);
        }
        if (job.getCron() != null && !job.getCron().isBlank()) {
            try {
                org.springframework.scheduling.support.CronExpression.parse(job.getCron().trim());
            } catch (IllegalArgumentException e) {
                throw new ServiceException(400, "cron 表达式非法：" + e.getMessage());
            }
            job.setCron(job.getCron().trim());
        } else {
            job.setCron(null);
        }
        validateDependency(job);
        if (job.getStatus() == null) {
            job.setStatus(DatSyncJob.STATUS_ENABLED);
        }
    }

    /** 依赖编排校验：前置任务存在、非自身、不成环（沿依赖链上溯）；0 = 无前置 */
    private void validateDependency(DatSyncJob job) {
        if (job.getDepJobId() == null || job.getDepJobId() <= 0) {
            job.setDepJobId(null);
            return;
        }
        if (job.getDepJobId().equals(job.getId())) {
            throw new ServiceException(400, "前置任务不能是自己");
        }
        DatSyncJob dep = jobMapper.selectById(job.getDepJobId());
        if (dep == null) {
            throw new ServiceException(400, "前置任务不存在");
        }
        Long cursor = dep.getDepJobId();
        int hops = 0;
        while (cursor != null && hops < 50) {
            if (cursor.equals(job.getId())) {
                throw new ServiceException(400, "依赖成环：前置任务链回指本任务");
            }
            DatSyncJob next = jobMapper.selectById(cursor);
            cursor = next == null ? null : next.getDepJobId();
            hops++;
        }
    }
}
