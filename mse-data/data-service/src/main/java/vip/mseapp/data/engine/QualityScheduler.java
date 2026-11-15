package vip.mseapp.data.engine;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import vip.mseapp.core.concurrent.TenantTaskGates;
import vip.mseapp.core.schedule.MseScheduled;
import vip.mseapp.core.schedule.MseTaskScheduler;
import vip.mseapp.core.schedule.TaskDefinition;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.data.entity.DatQualityRule;
import vip.mseapp.data.mapper.DatQualityRuleMapper;
import vip.mseapp.data.service.QualityService;
import vip.mseapp.mybatis.datasource.TenantDsRegistry;
import vip.mseapp.mybatis.schema.MasterSchemaAutoMigration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 质量规则对账调度器：与同步对账同款（扫配置表 → 增删改映射为框架调度任务，动态注册精确排程）。
 *
 * <p>每 30 秒对账 dat_quality_rule 全量行（跨主库与独立租户库遍历）：启用且有合法 cron →
 * {@link MseTaskScheduler#registerOrUpdate} 登记为独立任务——触发节奏由调度器按任务自身
 * cron 精确排程（与对账节拍解耦）；停用 → pause、再启用 → resume、删除 → unregister。
 * 任务体按行租户 runAs 回放并经租户并发闸门提交业务执行池（满则本轮跳过、下个 cron 点再来）；
 * 规则体自身另有内存防重入（QualityService），cron 周期小于运行时长时不重叠执行。
 */
@Component
public class QualityScheduler {

    private static final Logger log = LoggerFactory.getLogger(QualityScheduler.class);

    /** 租户任务 taskId 前缀：{@code data-quality:{ruleId}} */
    static final String TASK_PREFIX = "data-quality:";

    private final DatQualityRuleMapper ruleMapper;
    private final QualityService qualityService;
    private final ObjectProvider<MseTaskScheduler> schedulerProvider;
    private final ExecutorService syncExecutor;
    private final TenantTaskGates gates;
    private final ObjectProvider<TenantDsRegistry> registryProvider;

    /** 平台库数据源 key（primary=平台库，循环跳过；data 无平台表，首扫按缺表 DEBUG 跳过） */
    private final String platformDsKey;

    /** ruleId → 本地登记态（对账停用过才由对账恢复，防覆盖管理面单独暂停） */
    private final Map<Long, Registered> registered = new ConcurrentHashMap<>();

    private record Registered(String taskId, boolean pausedByReconcile) {
    }

    public QualityScheduler(DatQualityRuleMapper ruleMapper, QualityService qualityService,
                            ObjectProvider<MseTaskScheduler> schedulerProvider,
                            ExecutorService syncExecutor,
                            @Qualifier("dataTenantTaskGates") TenantTaskGates gates,
                            ObjectProvider<TenantDsRegistry> registryProvider,
                            @Value("${mse.tenant.schema.platform-ds:platform}") String platformDsKey) {
        this.ruleMapper = ruleMapper;
        this.qualityService = qualityService;
        this.schedulerProvider = schedulerProvider;
        this.syncExecutor = syncExecutor;
        this.gates = gates;
        this.registryProvider = registryProvider;
        this.platformDsKey = platformDsKey == null || platformDsKey.isBlank() ? "platform" : platformDsKey.trim();
    }

    @MseScheduled(taskId = "data-quality-scheduler", name = "质量规则调度对账",
            description = "对账 dat_quality_rule 增删改：注册/改期/启停/注销租户质量任务（实际节奏由各任务自身 cron 精确排程）",
            fixedDelay = 30_000, initialDelay = 45_000)
    public void tick() {
        MseTaskScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return; // mode=none/外部适配器未提供调度器：无处登记（该模式下本任务亦不排程，理论到不了这里）
        }
        // 双保险门禁：统一延迟启动已晚于结构迁移，此处兜底防极端场景（表尚未直建跳过本轮，下周期自愈）
        if (!MasterSchemaAutoMigration.isSchemaReady()) {
            log.debug("结构迁移未完成（首次启动建表中），本轮质量规则对账跳过");
            return;
        }
        List<DatQualityRule> rules = new ArrayList<>();
        // 首扫 primary（在线=平台库缺表跳过；离线=本地单库即全部世界）——全量行（含停用，供判删除）
        rules.addAll(scanRulesInCurrentDs("primary"));
        // 各注册租户库（内置共享库 + 独立租户世界）
        TenantDsRegistry registry = registryProvider.getIfAvailable();
        if (registry != null) {
            for (String dsKey : registry.registeredKeys()) {
                if (dsKey.equals(platformDsKey) || registry.isMainDbAlias(dsKey)) {
                    continue; // 平台库即 primary：首扫已覆盖；与共享库同库的别名：共享池轮已覆盖
                }
                DynamicDataSourceContextHolder.push(dsKey);
                try {
                    rules.addAll(scanRulesInCurrentDs(dsKey));
                } finally {
                    DynamicDataSourceContextHolder.poll();
                }
            }
        }
        reconcile(scheduler, rules);
    }

    /** 当前数据源内扫全部规则（runIgnore 免行级条件，行内 tenant_id 即回放锚点）；缺表等数据源级异常降级跳过 */
    private List<DatQualityRule> scanRulesInCurrentDs(String dsKey) {
        try {
            return TenantContext.runIgnore(() -> ruleMapper.selectList(new LambdaQueryWrapper<>()));
        } catch (DataAccessException e) {
            // 缺库/缺表 = 平台库无 data 表、独立库注册可见早于初始化直建的预期中间态，DEBUG 免周期噪声
            log.debug("数据源 {} 质量规则对账扫描跳过（表未就绪）", dsKey);
            return List.of();
        } catch (Exception e) {
            log.warn("数据源 {} 质量规则对账扫描失败（本轮跳过）", dsKey, e);
            return List.of();
        }
    }

    /** 对账核心：启用且 cron 合法 → 注册/改期；停用或 cron 缺失/非法 → 暂停；本轮未见 → 注销 */
    private void reconcile(MseTaskScheduler scheduler, List<DatQualityRule> rules) {
        Set<Long> seen = new HashSet<>();
        for (DatQualityRule rule : rules) {
            seen.add(rule.getId());
            Registered state = registered.get(rule.getId());
            String taskId = TASK_PREFIX + rule.getId();
            String cron = validCron(rule);
            boolean enabled = rule.getStatus() == DatQualityRule.STATUS_ENABLED && cron != null;
            if (enabled) {
                scheduler.registerOrUpdate(definition(rule, cron), body(rule));
                if (state == null) {
                    registered.put(rule.getId(), new Registered(taskId, false));
                } else if (state.pausedByReconcile()) {
                    scheduler.resume(taskId);
                    registered.put(rule.getId(), new Registered(taskId, false));
                    log.info("恢复质量任务 {}（配置重新启用）", taskId);
                }
            } else if (state != null && !state.pausedByReconcile()) {
                scheduler.pause(taskId);
                registered.put(rule.getId(), new Registered(taskId, true));
                log.info("暂停质量任务 {}（配置停用或 cron 缺失/非法）", taskId);
            }
        }
        Set<Long> vanished = new HashSet<>(registered.keySet());
        vanished.removeAll(seen);
        for (Long id : vanished) {
            Registered state = registered.remove(id);
            if (state != null) {
                scheduler.unregister(state.taskId());
                log.info("注销质量任务 {}（配置已删除）", state.taskId());
            }
        }
    }

    /** cron 合法性校验（空白/非法告警并返回 null，调用方按停用处理） */
    private static String validCron(DatQualityRule rule) {
        String cron = rule.getCron();
        if (cron == null || cron.isBlank()) {
            return null;
        }
        try {
            CronExpression.parse(cron.trim());
            return cron.trim();
        } catch (IllegalArgumentException e) {
            log.warn("质量规则 {} cron 非法（{}），暂不登记", rule.getId(), cron);
            return null;
        }
    }

    private static TaskDefinition definition(DatQualityRule rule, String cron) {
        return TaskDefinition.cron(TASK_PREFIX + rule.getId(),
                "质量规则[" + rule.getName() + "]（租户" + rule.getTenantId() + "）",
                "租户质量规则 dat_quality_rule#" + rule.getId() + "，由 data-quality-scheduler 对账登记",
                cron, true);
    }

    /** 任务体：按行租户回放 + 租户并发闸门提交业务执行池（满则跳过本轮，下个 cron 点再来） */
    private MseTaskScheduler.TaskRunnable body(DatQualityRule rule) {
        Long tenantId = rule.getTenantId();
        Long ruleId = rule.getId();
        return () -> TenantContext.runAs(tenantId, () -> {
            if (!gates.tryRun(tenantId, syncExecutor, () -> qualityService.run(ruleId, "sched"))) {
                log.info("租户 {} 质量并发已满，规则 {} 本轮跳过", tenantId, ruleId);
            }
        });
    }
}
