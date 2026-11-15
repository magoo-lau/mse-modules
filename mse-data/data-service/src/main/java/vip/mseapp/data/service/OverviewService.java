package vip.mseapp.data.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.entity.DatApiApp;
import vip.mseapp.data.entity.DatApiLog;
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.entity.DatMetaTable;
import vip.mseapp.data.entity.DatQualityRule;
import vip.mseapp.data.entity.DatSyncJob;
import vip.mseapp.data.mapper.DatApiAppMapper;
import vip.mseapp.data.mapper.DatApiLogMapper;
import vip.mseapp.data.mapper.DatApiMapper;
import vip.mseapp.data.mapper.DatDatasourceMapper;
import vip.mseapp.data.mapper.DatMetaTableMapper;
import vip.mseapp.data.mapper.DatQualityRuleMapper;
import vip.mseapp.data.mapper.DatSyncJobMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据中台概览：资产计数 + 今日调用量 + 最近运行/调用记录。
 */
@Service
public class OverviewService {

    private final DatDatasourceMapper datasourceMapper;
    private final DatSyncJobMapper syncJobMapper;
    private final DatMetaTableMapper metaTableMapper;
    private final DatApiMapper apiMapper;
    private final DatApiAppMapper appMapper;
    private final DatApiLogMapper apiLogMapper;
    private final DatQualityRuleMapper qualityRuleMapper;

    public OverviewService(DatDatasourceMapper datasourceMapper, DatSyncJobMapper syncJobMapper,
                           DatMetaTableMapper metaTableMapper, DatApiMapper apiMapper,
                           DatApiAppMapper appMapper, DatApiLogMapper apiLogMapper,
                           DatQualityRuleMapper qualityRuleMapper) {
        this.datasourceMapper = datasourceMapper;
        this.syncJobMapper = syncJobMapper;
        this.metaTableMapper = metaTableMapper;
        this.apiMapper = apiMapper;
        this.appMapper = appMapper;
        this.apiLogMapper = apiLogMapper;
        this.qualityRuleMapper = qualityRuleMapper;
    }

    public Overview overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return new Overview(
                datasourceMapper.selectCount(null),
                syncJobMapper.selectCount(new LambdaQueryWrapper<DatSyncJob>()
                        .eq(DatSyncJob::getStatus, DatSyncJob.STATUS_ENABLED)),
                metaTableMapper.selectCount(new LambdaQueryWrapper<DatMetaTable>()
                        .eq(DatMetaTable::getCategory, DatMetaTable.CATEGORY_ODS)),
                metaTableMapper.selectCount(new LambdaQueryWrapper<DatMetaTable>()
                        .eq(DatMetaTable::getCategory, DatMetaTable.CATEGORY_SOURCE)),
                apiMapper.selectCount(new LambdaQueryWrapper<DatApi>()
                        .eq(DatApi::getStatus, DatApi.STATUS_PUBLISHED)),
                appMapper.selectCount(new LambdaQueryWrapper<DatApiApp>()
                        .eq(DatApiApp::getStatus, DatApiApp.STATUS_ENABLED)),
                apiLogMapper.selectCount(new LambdaQueryWrapper<DatApiLog>()
                        .ge(DatApiLog::getCreateTime, todayStart)),
                qualityRuleMapper.selectCount(new LambdaQueryWrapper<DatQualityRule>()
                        .eq(DatQualityRule::getStatus, DatQualityRule.STATUS_ENABLED)),
                qualityRuleMapper.selectCount(new LambdaQueryWrapper<DatQualityRule>()
                        .eq(DatQualityRule::getLastPass, 0)),
                syncJobLogs(),
                apiLogs());
    }

    public List<DatSyncJobLogLite> syncJobLogs() {
        return syncJobMapper.selectList(new LambdaQueryWrapper<DatSyncJob>()
                        .isNotNull(DatSyncJob::getLastRunTime)
                        .orderByDesc(DatSyncJob::getLastRunTime)
                        .last("LIMIT 8")).stream()
                .map(job -> new DatSyncJobLogLite(job.getId(), job.getName(),
                        job.getLastRunTime(), job.getRowCount()))
                .toList();
    }

    public List<DatApiLog> apiLogs() {
        return apiLogMapper.selectList(new LambdaQueryWrapper<DatApiLog>()
                .orderByDesc(DatApiLog::getId)
                .last("LIMIT 8"));
    }

    public record DatSyncJobLogLite(Long id, String name, LocalDateTime lastRunTime, Long rowCount) {
    }

    public record Overview(Long datasourceCount, Long enabledJobCount, Long odsTableCount,
                           Long sourceTableCount, Long publishedApiCount, Long appCount,
                           Long todayApiCalls, Long qualityRuleCount, Long qualityBadRuleCount,
                           List<DatSyncJobLogLite> recentSyncLogs,
                           List<DatApiLog> recentApiLogs) {
    }
}
