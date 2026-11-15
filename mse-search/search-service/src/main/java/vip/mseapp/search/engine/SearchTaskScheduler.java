package vip.mseapp.search.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import vip.mseapp.core.schedule.MseScheduled;
import org.springframework.stereotype.Component;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.redis.config.MseRedisAutoConfiguration;
import vip.mseapp.search.config.SearchProperties;
import vip.mseapp.search.entity.SearchTask;
import vip.mseapp.search.mapper.SearchTaskMapper;
import vip.mseapp.search.service.SearchTaskService;
import vip.mseapp.search.tenant.SearchTenantRegistry;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 闲时任务调度器：按归属租户遍历轮询待执行任务并派发——idleOnly=1 的重任务（全量重建/
 * 索引优化）仅在闲时窗口（mse.search.idle-window）内执行，工作时间触发则保持待执行等待窗口；
 * SCAN_NOW 不受窗口限制。执行在工作线程重新建立归属上下文（runAs 任务行租户），多实例
 * 防重入：任务状态 CAS 抢占 + LockOps 双保险。
 */
@Component
@ConditionalOnProperty(prefix = "mse.search.task", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchTaskScheduler.class);

    private final SearchTaskMapper taskMapper;
    private final SearchTaskService taskService;
    private final SearchProperties properties;
    private final SearchTenantRegistry tenantRegistry;
    private final ExecutorService searchExecutor;
    private final MseRedisAutoConfiguration.LockOps lockOps;
    private final IdleWindows idleWindows;

    public SearchTaskScheduler(SearchTaskMapper taskMapper, SearchTaskService taskService,
                               SearchProperties properties, SearchTenantRegistry tenantRegistry,
                               ExecutorService searchExecutor,
                               MseRedisAutoConfiguration.LockOps lockOps) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.properties = properties;
        this.tenantRegistry = tenantRegistry;
        this.searchExecutor = searchExecutor;
        this.lockOps = lockOps;
        this.idleWindows = new IdleWindows(properties.getIdleWindow());
    }

    @MseScheduled(taskId = "search-task-poll", name = "搜索任务调度轮询",
            description = "按租户轮询待执行 search_task 并派发（闲时窗口内才跑 idleOnly 任务）",
            fixedDelayString = "${mse.search.task.poll-interval-ms:30000}",
            initialDelayString = "${mse.search.task.initial-delay-ms:30000}")
    public void tick() {
        boolean inIdleWindow = idleWindows.contains(LocalTime.now());
        for (Long tenantId : tenantRegistry.contextTenantIds()) {
            try {
                TenantContext.runAs(tenantId, () -> {
                    List<SearchTask> pending = taskMapper.selectList(new LambdaQueryWrapper<SearchTask>()
                            .eq(SearchTask::getStatus, SearchTask.STATUS_PENDING)
                            .orderByAsc(SearchTask::getId));
                    for (SearchTask task : pending) {
                        dispatch(tenantId, task, inIdleWindow);
                    }
                });
            } catch (Exception e) {
                log.warn("[mse-search] 租户 {} 任务调度失败: {}", tenantId, e.getMessage());
            }
        }
    }

    private void dispatch(Long tenantId, SearchTask task, boolean inIdleWindow) {
        boolean idleOnly = task.getIdleOnly() != null && task.getIdleOnly() == 1;
        if (idleOnly && !inIdleWindow) {
            return; // 闲时专用任务等待窗口
        }
        searchExecutor.submit(() -> {
            try {
                lockOps.tryLockExecute("mse:search:task:" + task.getId(),
                        () -> {
                            taskService.execute(task);
                            return null;
                        });
            } catch (Exception e) {
                log.debug("[mse-search] 租户 {} 任务 {} 跳过或失败: {}", tenantId, task.getId(), e.getMessage());
            }
        });
    }
}
