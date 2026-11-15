package vip.mseapp.monitor.service;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import vip.mseapp.core.schedule.MseScheduled;
import org.springframework.stereotype.Service;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.entity.MonRecord;
import vip.mseapp.monitor.mapper.MonRecordMapper;
import vip.mseapp.monitor.support.SchemaNotReady;
import vip.mseapp.mybatis.datasource.TenantDsRegistry;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采样记录查询与保留期清理（每日 04:30 删除 retention-days 之前的记录）。
 *
 * <p>租户语义：趋势查询按会话上下文隔离（平台看平台库、租户看绑定库）；
 * 保留期清理为全局运维任务：平台库轮显式 push 平台数据源逐库清理（runIgnore 裸扫会被路由
 * 拦截器改道到内置共享库防错库——平台库必须显式 push 才清得到），再遍历其余已注册动态池。
 */
@Service
public class RecordService {

    private static final Logger log = LoggerFactory.getLogger(RecordService.class);

    private final MonRecordMapper recordMapper;
    private final MonitorProperties properties;
    private final ObjectProvider<TenantDsRegistry> registryProvider;
    private final ObjectProvider<DynamicRoutingDataSource> routingProvider;
    private final vip.mseapp.monitor.support.MonitorWorld world;

    /** 平台库数据源 key（显式 push 清理，循环跳过） */
    private final String platformDsKey;

    public RecordService(MonRecordMapper recordMapper, MonitorProperties properties,
                         ObjectProvider<TenantDsRegistry> registryProvider,
                         ObjectProvider<DynamicRoutingDataSource> routingProvider,
                         vip.mseapp.monitor.support.MonitorWorld world,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${mse.tenant.schema.platform-ds:platform}") String platformDsKey) {
        this.recordMapper = recordMapper;
        this.properties = properties;
        this.registryProvider = registryProvider;
        this.routingProvider = routingProvider;
        this.world = world;
        this.platformDsKey = platformDsKey == null || platformDsKey.isBlank() ? "platform" : platformDsKey.trim();
    }

    /** 目标趋势数据（升序；最多 3000 点）。在线按会话上下文隔离；离线为本地单一监控世界 */
    public List<MonRecord> list(Long targetId, int hours) {
        int range = switch (hours) {
            case 24 -> 24;
            case 168 -> 168;
            default -> 1;
        };
        return world.scope(() -> recordMapper.selectList(
                new LambdaQueryWrapper<MonRecord>()
                        .eq(MonRecord::getTargetId, targetId)
                        .ge(MonRecord::getCollectTime, LocalDateTime.now().minusHours(range))
                        .orderByAsc(MonRecord::getCollectTime)
                        .last("LIMIT 3000")));
    }

    @MseScheduled(taskId = "monitor-record-cleanup", name = "监控记录过期清理",
            description = "按保留期删除各库过期的 mon_record（缺省每日 04:30）",
            cron = "${mse.monitor.cleanup-cron:0 30 4 * * ?}")
    public void cleanupExpired() {
        int days = Math.max(1, properties.getRetentionDays());
        // 离线模式：监控数据全在本地缺省库（runIgnore 裸扫落本地），单轮即全量
        if (world.offline()) {
            cleanupCurrentDs(days);
            return;
        }
        // 平台库轮显式 push：裸 runIgnore 会被路由改道内置共享库，平台库漏 push 即永不被清理
        try {
            cleanupPushed(registeredDs(platformDsKey) ? platformDsKey : null, days);
        } catch (Exception e) {
            logCleanupFailure("primary(平台库)", e);
        }
        TenantDsRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        for (String dsKey : registry.registeredKeys()) {
            if (dsKey.equals(platformDsKey)) {
                continue; // 平台库：上轮已显式清理
            }
            if (registry.isMainDbAlias(dsKey)) {
                continue; // 与缺省共享库同库：共享池轮已清理
            }
            try {
                cleanupPushed(dsKey, days);
            } catch (Exception e) {
                logCleanupFailure(dsKey, e);
            }
        }
    }

    /** push 指定数据源后清理；key 为 null（平台库未注册的本地拓扑）回落裸扫，按 runIgnore 落缺省共享库语义 */
    private void cleanupPushed(String dsKey, int days) {
        if (dsKey == null) {
            cleanupCurrentDs(days);
            return;
        }
        DynamicDataSourceContextHolder.push(dsKey);
        try {
            cleanupCurrentDs(days);
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }

    private boolean registeredDs(String dsKey) {
        DynamicRoutingDataSource routing = routingProvider.getIfAvailable();
        return routing != null && routing.getDataSources().containsKey(dsKey);
    }

    /** 单库清理失败不影响其余库，下轮重试；缺库/缺表 = 独立库注册早于初始化直建的预期中间态 */
    private void logCleanupFailure(String dsKey, Exception e) {
        if (SchemaNotReady.isDatabaseMissing(e)) {
            log.debug("数据源 {} 租户库尚未创建（等初始化直建），本轮跳过", dsKey);
        } else if (SchemaNotReady.isTableMissing(e)) {
            log.debug("数据源 {} mon_record 表未就绪（等懒初始化直建），本轮跳过", dsKey);
        } else {
            log.warn("数据源 {} 监控记录清理失败（本轮跳过）", dsKey, e);
        }
    }

    private void cleanupCurrentDs(int days) {
        // 调度线程无会话租户，runIgnore 免行级条件（整库即该数据源自身范围）
        TenantContext.runIgnore(() -> recordMapper.delete(new LambdaQueryWrapper<MonRecord>()
                .lt(MonRecord::getCollectTime, LocalDateTime.now().minusDays(days))));
    }
}
