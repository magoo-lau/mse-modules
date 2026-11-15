package vip.mseapp.search.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import vip.mseapp.core.schedule.MseScheduled;
import vip.mseapp.core.schedule.MseTaskScheduler;
import vip.mseapp.core.schedule.TaskDefinition;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.redis.config.MseRedisAutoConfiguration;
import vip.mseapp.search.config.SearchProperties;
import vip.mseapp.search.entity.SearchSource;
import vip.mseapp.search.mapper.SearchSourceMapper;
import vip.mseapp.search.tenant.SearchTenantRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 增量扫描对账调度器：扫源目录 → 增删改映射为框架调度任务（动态注册精确排程，范式同 mse-data）。
 *
 * <p>目录真租户化后按「租户 × 源」对账：每 pollIntervalMs 遍历「平台 + 全部租户」归属上下文
 * （{@code runAs}，路由拦截器切归属库）读各目录全量源，启用且有合法 cron →
 * {@link MseTaskScheduler#registerOrUpdate} 登记为独立任务——触发节奏由调度器按源自身
 * scanCron 精确排程（与对账节拍解耦，新源在下个 cron 时点自然首扫）；停用 → pause、
 * 再启用 → resume、删除（或租户世界消失）→ unregister。任务体提交业务执行池并经
 * LockOps 按租户×源 Redis 锁防多实例同源并发（获取不到锁立即让位，下个时点再试）。
 */
@Component
@ConditionalOnProperty(prefix = "mse.search.scan", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchScanScheduler.class);

    /** 租户任务 taskId 前缀：{@code search-scan:{tenantId}:{sourceCode}} */
    static final String TASK_PREFIX = "search-scan:";

    private final SearchSourceMapper sourceMapper;
    private final ScanService scanService;
    private final SearchProperties properties;
    private final SearchTenantRegistry tenantRegistry;
    private final ObjectProvider<MseTaskScheduler> schedulerProvider;
    private final ExecutorService searchExecutor;
    private final MseRedisAutoConfiguration.LockOps lockOps;

    /** dueKey（tenantId:sourceCode）→ 本地登记态（对账停用过才由对账恢复，防覆盖管理面单独暂停） */
    private final Map<String, Registered> registered = new ConcurrentHashMap<>();

    private record Registered(String taskId, boolean pausedByReconcile) {
    }

    public SearchScanScheduler(SearchSourceMapper sourceMapper, ScanService scanService,
                               SearchProperties properties, SearchTenantRegistry tenantRegistry,
                               ObjectProvider<MseTaskScheduler> schedulerProvider,
                               ExecutorService searchExecutor,
                               MseRedisAutoConfiguration.LockOps lockOps) {
        this.sourceMapper = sourceMapper;
        this.scanService = scanService;
        this.properties = properties;
        this.tenantRegistry = tenantRegistry;
        this.schedulerProvider = schedulerProvider;
        this.searchExecutor = searchExecutor;
        this.lockOps = lockOps;
    }

    @MseScheduled(taskId = "search-scan", name = "索引增量扫描对账",
            description = "对账 search_source 增删改：注册/改期/启停/注销租户×源扫描任务（实际节奏由各源自身 cron 精确排程）",
            fixedDelayString = "${mse.search.scan.poll-interval-ms:20000}",
            initialDelayString = "${mse.search.scan.initial-delay-ms:30000}")
    public void tick() {
        MseTaskScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return; // mode=none/外部适配器未提供调度器：无处登记（该模式下本任务亦不排程，理论到不了这里）
        }
        Set<String> seen = new HashSet<>();
        for (Long tenantId : tenantRegistry.contextTenantIds()) {
            try {
                // 全量源（含停用，供判删除；归属上下文内读目录——平台 0 = 平台库或主库平台行）
                List<SearchSource> sources = TenantContext.runAs(tenantId, () ->
                        sourceMapper.selectList(new LambdaQueryWrapper<>()));
                reconcile(scheduler, tenantId, sources, seen);
            } catch (Exception e) {
                log.warn("[mse-search] 租户 {} 源对账失败: {}", tenantId, e.getMessage());
            }
        }
        // 本轮未见的键 = 源删除或租户世界消失 → 注销
        Set<String> vanished = new HashSet<>(registered.keySet());
        vanished.removeAll(seen);
        for (String dueKey : vanished) {
            Registered state = registered.remove(dueKey);
            if (state != null) {
                scheduler.unregister(state.taskId());
                log.info("[mse-search] 注销扫描任务 {}（源已删除或租户世界消失）", state.taskId());
            }
        }
    }

    /** 对账核心：启用且 cron 合法 → 注册/改期；停用或 cron 非法 → 暂停；消失 → 注销（tick 收口） */
    private void reconcile(MseTaskScheduler scheduler, Long tenantId, List<SearchSource> sources, Set<String> seen) {
        for (SearchSource source : sources) {
            String dueKey = tenantId + ":" + source.getSourceCode();
            seen.add(dueKey);
            Registered state = registered.get(dueKey);
            String taskId = TASK_PREFIX + dueKey;
            String cron = validCron(source);
            boolean enabled = source.getStatus() == SearchSource.STATUS_ENABLED && cron != null;
            if (enabled) {
                scheduler.registerOrUpdate(definition(tenantId, source, cron), body(tenantId, source));
                if (state == null) {
                    registered.put(dueKey, new Registered(taskId, false));
                } else if (state.pausedByReconcile()) {
                    scheduler.resume(taskId);
                    registered.put(dueKey, new Registered(taskId, false));
                    log.info("[mse-search] 恢复扫描任务 {}（配置重新启用）", taskId);
                }
            } else if (state != null && !state.pausedByReconcile()) {
                scheduler.pause(taskId);
                registered.put(dueKey, new Registered(taskId, true));
                log.info("[mse-search] 暂停扫描任务 {}（配置停用或 cron 缺失/非法）", taskId);
            }
        }
    }

    /** 生效 cron（空白回落缺省）与合法性校验；非法告警并返回 null，调用方按停用处理 */
    private String validCron(SearchSource source) {
        String cron = source.getScanCron();
        String effective = cron == null || cron.isBlank() ? properties.getScan().getDefaultCron() : cron.trim();
        try {
            CronExpression.parse(effective);
            return effective;
        } catch (IllegalArgumentException e) {
            log.warn("[mse-search] 源 {} cron 非法（{}），暂不登记", source.getSourceCode(), effective);
            return null;
        }
    }

    private TaskDefinition definition(Long tenantId, SearchSource source, String cron) {
        return TaskDefinition.cron(TASK_PREFIX + tenantId + ":" + source.getSourceCode(),
                "索引扫描[" + source.getSourceName() + "]（租户" + tenantId + "）",
                "租户源增量扫描 search_source#" + source.getSourceCode() + "（租户 " + tenantId + "），由 search-scan 对账登记",
                cron, true);
    }

    /** 任务体：提交业务执行池 + Redis 锁按租户×源防多实例并发（抢不到即让位，下个时点再试） */
    private MseTaskScheduler.TaskRunnable body(Long tenantId, SearchSource source) {
        String sourceCode = source.getSourceCode();
        String dueKey = tenantId + ":" + sourceCode;
        return () -> searchExecutor.submit(() -> {
            try {
                lockOps.tryLockExecute("mse:search:scan:" + dueKey,
                        () -> TenantContext.runAs(tenantId, () -> scanService.scanSource(
                                sourceCode, properties.getScan().getMaxBatchesPerTick(), null)));
            } catch (Exception e) {
                log.debug("[mse-search] 租户 {} 源 {} 扫描跳过或失败: {}",
                        tenantId, sourceCode, e.getMessage());
            }
        });
    }
}
