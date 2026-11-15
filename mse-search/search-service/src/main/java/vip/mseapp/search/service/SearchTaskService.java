package vip.mseapp.search.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.search.engine.EsIndexManager;
import vip.mseapp.search.engine.ScanService;
import vip.mseapp.search.engine.SearchEngineClient;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.entity.SearchTask;
import vip.mseapp.search.mapper.SearchSourceMapper;
import vip.mseapp.search.mapper.SearchTaskMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 搜索引擎任务：全量重建（删本租户索引重建 + 水位归零重扫，保证无孤儿文档）/
 * 索引优化（force-merge）/ 立即扫描。执行由闲时任务调度器驱动（idleOnly=1 仅闲时窗口）；
 * 执行以任务行归属租户上下文运行（runAs，源行/游标/索引均按归属生效）。
 */
@Service
public class SearchTaskService {

    private static final Logger log = LoggerFactory.getLogger(SearchTaskService.class);

    private final SearchTaskMapper taskMapper;
    private final SearchSourceMapper sourceMapper;
    private final ScanService scanService;
    private final SearchEngineClient engineClient;
    private final EsIndexManager indexManager;

    public SearchTaskService(SearchTaskMapper taskMapper, SearchSourceMapper sourceMapper,
                             ScanService scanService, SearchEngineClient engineClient,
                             EsIndexManager indexManager) {
        this.taskMapper = taskMapper;
        this.sourceMapper = sourceMapper;
        this.scanService = scanService;
        this.engineClient = engineClient;
        this.indexManager = indexManager;
    }

    /** 创建任务（管理端触发） */
    public Long create(String taskType, String sourceCode, boolean idleOnly) {
        SearchTask task = new SearchTask();
        task.setTaskType(taskType);
        task.setSourceCode(sourceCode);
        task.setIdleOnly(idleOnly ? 1 : 0);
        task.setStatus(SearchTask.STATUS_PENDING);
        task.setProgress(0);
        taskMapper.insert(task);
        return task.getId();
    }

    public Page<SearchTask> page(long pageNum, long pageSize, Integer status, String taskType) {
        return taskMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SearchTask>()
                        .eq(status != null, SearchTask::getStatus, status)
                        .eq(taskType != null && !taskType.isBlank(), SearchTask::getTaskType, taskType)
                        .orderByDesc(SearchTask::getId));
    }

    /** 立即执行：待执行的闲时任务改为立即（不再等待闲时窗口），由调度器下一轮拾起 */
    public void runNow(Long id) {
        SearchTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new ServiceException(404, "任务不存在: " + id);
        }
        if (task.getStatus() != SearchTask.STATUS_PENDING) {
            throw new ServiceException(400, "仅待执行任务可立即执行");
        }
        if (task.getIdleOnly() != null && task.getIdleOnly() == 1) {
            task.setIdleOnly(0);
            taskMapper.updateById(task);
        }
    }

    /**
     * 执行任务（调度器在工作线程调用；状态以 CAS 语义从 PENDING 推进，防止多实例重复执行）。
     * 全程以任务行归属租户上下文运行（runAs，线程池任务重新建立语义）。
     */
    public void execute(SearchTask task) {
        Long tenantId = task.getTenantId() == null ? 0L : task.getTenantId();
        TenantContext.runAs(tenantId, () -> executeInContext(task));
    }

    private void executeInContext(SearchTask task) {
        SearchTask claimed = claim(task.getId());
        if (claimed == null) {
            return;
        }
        Long tenantId = claimed.getTenantId() == null ? 0L : claimed.getTenantId();
        claimed.setStartTime(LocalDateTime.now());
        taskMapper.updateById(claimed);
        try {
            long processed = switch (task.getTaskType()) {
                case SearchTask.TYPE_SCAN_NOW -> runScanNow(claimed);
                case SearchTask.TYPE_FULL_REBUILD -> runFullRebuild(claimed, tenantId);
                case SearchTask.TYPE_INDEX_OPTIMIZE -> runIndexOptimize(claimed, tenantId);
                default -> throw new ServiceException(400, "未知任务类型: " + task.getTaskType());
            };
            claimed.setStatus(SearchTask.STATUS_SUCCESS);
            claimed.setProgress(100);
            claimed.setProcessedRows(processed);
            claimed.setEndTime(LocalDateTime.now());
            taskMapper.updateById(claimed);
            log.info("[mse-search] 任务 {}（{}）完成，处理 {} 条", task.getId(), task.getTaskType(), processed);
        } catch (Exception e) {
            log.warn("[mse-search] 任务 {}（{}）失败", task.getId(), task.getTaskType(), e);
            claimed.setStatus(SearchTask.STATUS_FAILED);
            claimed.setErrorMsg(e.getMessage() == null ? "未知异常" :
                    e.getMessage().substring(0, Math.min(480, e.getMessage().length())));
            claimed.setEndTime(LocalDateTime.now());
            taskMapper.updateById(claimed);
        }
    }

    /** CAS 抢占：仅 PENDING → RUNNING 成功者执行 */
    private SearchTask claim(Long id) {
        SearchTask current = taskMapper.selectById(id);
        if (current == null || current.getStatus() != SearchTask.STATUS_PENDING) {
            return null;
        }
        current.setStatus(SearchTask.STATUS_RUNNING);
        current.setProgress(0);
        int updated = taskMapper.update(current, new LambdaQueryWrapper<SearchTask>()
                .eq(SearchTask::getId, id)
                .eq(SearchTask::getStatus, SearchTask.STATUS_PENDING));
        return updated == 1 ? current : null;
    }

    private long runScanNow(SearchTask task) {
        requireSource(task.getSourceCode());
        return scanOrThrow(task, task.getSourceCode());
    }

    /** 全量重建：删本租户索引重建（保证无孤儿文档）+ 游标水位归零 + 全量重扫 */
    private long runFullRebuild(SearchTask task, Long tenantId) {
        SearchSource source = requireSource(task.getSourceCode());
        String indexName = indexManager.indexName(source.getSourceCode(), tenantId);
        engineClient.deleteIndex(indexName);
        indexManager.onIndexRemoved(source.getSourceCode(), tenantId);
        indexManager.ensureIndex(source, tenantId);
        scanService.resetCursors(source.getSourceCode());
        return scanOrThrow(task, source.getSourceCode());
    }

    /** 任务通道扫描：分片失败不让任务报成功——有失败即抛出（失败原因回填任务行，防「成功 0 条」假象） */
    private long scanOrThrow(SearchTask task, String sourceCode) {
        List<ScanService.ShardFailure> failures = new ArrayList<>();
        long processed = scanService.scanSource(sourceCode, 0, progress(task), failures);
        if (!failures.isEmpty()) {
            ScanService.ShardFailure first = failures.get(0);
            throw new ServiceException(500, "分片 " + first.shardIndex() + " 扫描失败: " + first.error()
                    + (failures.size() > 1 ? "（共 " + failures.size() + " 个分片失败）" : ""));
        }
        return processed;
    }

    /** 索引优化：force-merge 单源或全部启用源（本租户索引） */
    private long runIndexOptimize(SearchTask task, Long tenantId) {
        List<SearchSource> targets = task.getSourceCode() == null || task.getSourceCode().isBlank()
                ? allEnabledSources()
                : List.of(requireSource(task.getSourceCode()));
        int done = 0;
        for (SearchSource source : targets) {
            engineClient.forceMerge(indexManager.indexName(source.getSourceCode(), tenantId));
            done++;
            if (progress(task) != null) {
                progress(task).accept(Math.min(99, done * 100 / targets.size()));
            }
        }
        return done;
    }

    private SearchSource requireSource(String sourceCode) {
        SearchSource source = sourceMapper.selectOne(new LambdaQueryWrapper<SearchSource>()
                .eq(SearchSource::getSourceCode, sourceCode));
        if (source == null) {
            throw new ServiceException(404, "可搜索源不存在: " + sourceCode);
        }
        return source;
    }

    private List<SearchSource> allEnabledSources() {
        return sourceMapper.selectList(new LambdaQueryWrapper<SearchSource>()
                .eq(SearchSource::getStatus, SearchSource.STATUS_ENABLED));
    }

    /** 进度回写（执行中任务，节流到整百分比变化时才 update） */
    private IntConsumer progress(SearchTask task) {
        return percent -> {
            try {
                SearchTask current = taskMapper.selectById(task.getId());
                if (current != null && current.getStatus() == SearchTask.STATUS_RUNNING
                        && (current.getProgress() == null || current.getProgress() != percent)) {
                    current.setProgress(percent);
                    taskMapper.updateById(current);
                }
            } catch (Exception ignored) {
                // 进度回写失败不影响任务执行
            }
        };
    }
}
