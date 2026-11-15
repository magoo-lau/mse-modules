package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.data.engine.SyncEngine;
import vip.mseapp.data.entity.DatLineage;
import vip.mseapp.data.entity.DatSyncJob;
import vip.mseapp.data.mapper.DatLineageMapper;

import java.util.List;

/**
 * 任务级血缘：同步任务保存/修改时刷新「源表 → ODS」记录，删除任务时清理；
 * 提供按表名的上下游双向查询（数据地图与同步任务共用）。
 */
@Service
public class LineageService {

    private final DatLineageMapper lineageMapper;

    public LineageService(DatLineageMapper lineageMapper) {
        this.lineageMapper = lineageMapper;
    }

    /** 任务保存/修改后刷新血缘（每任务一条，整体重建） */
    @Transactional(rollbackFor = Exception.class)
    public void refresh(DatSyncJob job) {
        lineageMapper.delete(new LambdaQueryWrapper<DatLineage>().eq(DatLineage::getJobId, job.getId()));
        DatLineage lineage = new DatLineage();
        lineage.setJobId(job.getId());
        lineage.setJobName(job.getName());
        lineage.setDatasourceId(job.getDatasourceId());
        lineage.setSourceType(job.getSourceType());
        lineage.setSourceTable(DatSyncJob.SOURCE_SQL.equals(job.getSourceType())
                ? summarize(job.getQuerySql()) : job.getSourceTable());
        lineage.setTargetTable(job.getTargetTable());
        lineageMapper.insert(lineage);
    }

    /** 任务删除后清理血缘 */
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long jobId) {
        lineageMapper.delete(new LambdaQueryWrapper<DatLineage>().eq(DatLineage::getJobId, jobId));
    }

    /** 按表名双向查询：作为来源被同步（downstream）与作为目标的来源（upstream） */
    public LineageView byTable(String tableName) {
        if (tableName == null || !tableName.matches(SyncEngine.IDENTIFIER_PATTERN)) {
            throw new ServiceException(400, "表名非法");
        }
        List<DatLineage> upstream = lineageMapper.selectList(new LambdaQueryWrapper<DatLineage>()
                .eq(DatLineage::getTargetTable, tableName));
        List<DatLineage> downstream = lineageMapper.selectList(new LambdaQueryWrapper<DatLineage>()
                .eq(DatLineage::getSourceTable, tableName));
        return new LineageView(tableName, upstream, downstream);
    }

    /** 自定义查询来源存摘要（截断），保证血缘行可读 */
    private String summarize(String querySql) {
        if (querySql == null) {
            return null;
        }
        String flat = querySql.replaceAll("\\s+", " ").trim();
        return flat.length() > 180 ? flat.substring(0, 180) + "…" : flat;
    }

    public record LineageView(String tableName, List<DatLineage> upstream, List<DatLineage> downstream) {
    }
}
