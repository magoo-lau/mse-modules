package vip.mseapp.monitor.engine;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import vip.mseapp.core.schedule.MseScheduled;
import org.springframework.stereotype.Component;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.mapper.MonTargetMapper;
import vip.mseapp.monitor.service.CollectService;
import vip.mseapp.monitor.service.TargetService;
import vip.mseapp.monitor.support.SchemaNotReady;
import vip.mseapp.mybatis.datasource.TenantDsRegistry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 采集调度器：固定轮询扫描「启用中且到达采集间隔」的目标，提交线程池并发探测。
 *
 * <p>租户语义（0.1.0 去默认租户库）：监控目标按归属分库存储（平台目标在平台库=primary，
 * 租户目标在缺省共享库/独立库的动态池），平台库轮显式 push 平台数据源扫描（runIgnore 裸扫会被
 * 路由拦截器改道到内置共享库防错库——平台库必须显式 push 才扫得到，否则只建在平台库的目标如
 * 本机 OS 永不被采集），再遍历全部已注册动态数据源（runIgnore 免行级条件取全库行，排除
 * tenant_id=0 平台行——平台目标只认平台库份，库内残留副本采了也只会经 runAs(0) 写回平台库
 * 且不受平台侧启停控制），采集落库由 {@link CollectService} 按目标归属租户路由——平台目标
 * 回写平台库，租户目标写回各自库，互不越界。
 *
 * <p>内存态调度（多实例部署需外置调度防重复采集）；inFlight 集合防止慢探测导致的任务堆积；
 * 首轮幂等补建本机 OS 目标（平台语义，租户侧 OS 目标由租户自建）。
 *
 * <p>未就绪库容错：独立库自注册进路由表到初始化直建（建库 → 懒初始化直建 mon_* 表）之间存在
 * 开放窗口（直连扫描不经过 TenantDsLazyInitializer），缺库/缺表属预期中间态——按
 * {@link SchemaNotReady} 识别后 DEBUG 安静跳过，初始化完成后自动纳入扫描；
 * 其余异常保持 WARN 带栈。
 */
@Component
public class MonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);

    private final MonTargetMapper targetMapper;
    private final CollectService collectService;
    private final TargetService targetService;
    private final ExecutorService collectExecutor;
    private final MonitorProperties properties;
    private final ObjectProvider<TenantDsRegistry> registryProvider;
    private final ObjectProvider<DynamicRoutingDataSource> routingProvider;

    /** 平台库数据源 key（与 mse.tenant.schema.platform-ds 同源，缺省 platform） */
    private final String platformDsKey;

    /** 采集中的目标 id（防上一轮未完成重复提交） */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean osEnsured = false;

    public MonitorScheduler(MonTargetMapper targetMapper, CollectService collectService,
                            TargetService targetService,
                            ExecutorService collectExecutor, MonitorProperties properties,
                            ObjectProvider<TenantDsRegistry> registryProvider,
                            ObjectProvider<DynamicRoutingDataSource> routingProvider,
                            @org.springframework.beans.factory.annotation.Value(
                                    "${mse.tenant.schema.platform-ds:platform}") String platformDsKey) {
        this.targetMapper = targetMapper;
        this.collectService = collectService;
        this.targetService = targetService;
        this.collectExecutor = collectExecutor;
        this.properties = properties;
        this.registryProvider = registryProvider;
        this.routingProvider = routingProvider;
        this.platformDsKey = platformDsKey;
    }

    @MseScheduled(taskId = "monitor-scan", name = "监控采集调度扫描",
            description = "遍历主库/平台库/租户库扫 mon_target 到期目标并提交采集",
            fixedDelayString = "${mse.monitor.scan-interval-ms:10000}", initialDelay = 15_000)
    public void tick() {
        ensureOsTargetOnce();
        List<MonTarget> targets = scanAllDataSources();
        for (MonTarget target : targets) {
            if (target.getId() == null || inFlight.contains(target.getId()) || !due(target)) {
                continue;
            }
            inFlight.add(target.getId());
            collectExecutor.submit(() -> {
                try {
                    collectService.collect(target);
                } catch (Exception e) {
                    log.warn("监控目标 {}（{}）采集失败", target.getId(), target.getName(), e);
                } finally {
                    inFlight.remove(target.getId());
                }
            });
        }
    }

    /**
     * 在线=平台库轮（显式 push 平台数据源——runIgnore 裸扫被路由改道内置共享库，平台库漏 push
     * 即永不被扫描）+ 遍历其余已注册动态数据源（跳过平台库与缺省共享库同库别名；排除 tenant_id=0
     * 平台行）。离线：监控世界收敛为本地缺省库——runIgnore 裸扫即落本地，只扫首轮。
     */
    private List<MonTarget> scanAllDataSources() {
        List<MonTarget> targets = new ArrayList<>();
        if (properties.isOffline()) {
            try {
                targets.addAll(scanCurrentDs(false));
            } catch (Exception e) {
                logScanFailure("本地缺省库", e);
            }
            return targets;
        }
        try {
            targets.addAll(scanPushed(registeredDs(platformDsKey) ? platformDsKey : null, false));
        } catch (Exception e) {
            logScanFailure("primary(平台库)", e);
        }
        TenantDsRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return targets;
        }
        for (String dsKey : registry.registeredKeys()) {
            if (dsKey.equals(platformDsKey)) {
                continue; // 平台库：上轮已显式扫描
            }
            if (registry.isMainDbAlias(dsKey)) {
                continue; // 与缺省共享库同库：共享池轮已覆盖
            }
            try {
                targets.addAll(scanPushed(dsKey, true));
            } catch (Exception e) {
                logScanFailure(dsKey, e);
            }
        }
        return targets;
    }

    /** push 指定数据源后扫描；key 为 null（如平台库未注册的本地拓扑）回落裸扫，按 runIgnore 落缺省共享库语义 */
    private List<MonTarget> scanPushed(String dsKey, boolean excludePlatformRows) {
        if (dsKey == null) {
            return scanCurrentDs(excludePlatformRows);
        }
        DynamicDataSourceContextHolder.push(dsKey);
        try {
            return scanCurrentDs(excludePlatformRows);
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }

    private boolean registeredDs(String dsKey) {
        DynamicRoutingDataSource routing = routingProvider.getIfAvailable();
        return routing != null && routing.getDataSources().containsKey(dsKey);
    }

    /** 缺库/缺表 = 独立库注册可见早于初始化直建的预期中间态，DEBUG 免每轮全栈噪声；其余异常 WARN 带栈 */
    private void logScanFailure(String dsKey, Exception e) {
        if (SchemaNotReady.isDatabaseMissing(e)) {
            log.debug("数据源 {} 租户库尚未创建（等初始化直建），本轮跳过", dsKey);
        } else if (SchemaNotReady.isTableMissing(e)) {
            log.debug("数据源 {} monitor 表未就绪（等懒初始化直建），本轮跳过", dsKey);
        } else {
            log.warn("数据源 {} 监控目标扫描失败（本轮跳过）", dsKey, e);
        }
    }

    /** excludePlatformRows：动态池轮排除平台行（tenant_id=0 的分库前残留副本，平台目标只认平台库份） */
    private List<MonTarget> scanCurrentDs(boolean excludePlatformRows) {
        return TenantContext.runIgnore(() -> targetMapper.selectList(
                new LambdaQueryWrapper<MonTarget>()
                        .eq(MonTarget::getEnabled, MonTarget.ENABLED)
                        .ne(excludePlatformRows, MonTarget::getTenantId, 0L)));
    }

    private boolean due(MonTarget target) {
        if (target.getStatusTime() == null) {
            return true;
        }
        int interval = target.getCollectInterval() == null
                ? properties.getDefaultCollectInterval() : target.getCollectInterval();
        return target.getStatusTime().plusSeconds(interval).isBefore(LocalDateTime.now());
    }

    /** 缺库/缺表 = 首启建表完成前的预期中间态，DEBUG 免全栈噪声（对齐 logScanFailure 分级）；其余异常 WARN 带栈 */
    private void ensureOsTargetOnce() {
        if (osEnsured) {
            return;
        }
        try {
            targetService.ensureOsTarget();
            osEnsured = true;
        } catch (Exception e) {
            if (SchemaNotReady.isDatabaseMissing(e) || SchemaNotReady.isTableMissing(e)) {
                log.debug("mon_* 表尚未就绪（首启建表中），本机 OS 目标本轮跳过");
            } else {
                log.warn("本机 OS 监控目标创建失败（下轮重试）", e);
            }
        }
    }
}
