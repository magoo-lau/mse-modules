package vip.mseapp.monitor.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.monitor.config.MonitorProperties;
import vip.mseapp.monitor.engine.probe.Probe;
import vip.mseapp.monitor.engine.probe.ProbeResult;
import vip.mseapp.monitor.entity.MonRecord;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.mapper.MonRecordMapper;
import vip.mseapp.monitor.mapper.MonTargetMapper;
import vip.mseapp.monitor.support.MonitorWorld;
import vip.mseapp.monitor.support.SimpleHttp;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 采集编排：选择探测器 → 执行 → 采样落库（mon_record）+ 目标状态快照回写（mon_target）→ 告警评估。
 *
 * <p>调度线程与手动「立即检测」共用本入口。租户语义 = 目标归属租户：整条链路在
 * {@code TenantContext.runAs(target.tenantId)} 内执行——平台目标（tenant 0）落平台库，
 * 租户目标经路由拦截器切到绑定库（独立库/共享库），行级插件同步注入 tenant_id 条件；
 * 通知设置/告警/采样记录随目标归属各自分库存储。
 */
@Service
public class CollectService {

    private final List<Probe> probes;
    private final MonRecordMapper recordMapper;
    private final MonTargetMapper targetMapper;
    private final AlertService alertService;
    private final JsonMapper jsonMapper;
    private final MonitorProperties properties;
    private final MonitorWorld world;

    public CollectService(List<Probe> probes, MonRecordMapper recordMapper,
                          MonTargetMapper targetMapper, AlertService alertService,
                          JsonMapper jsonMapper, MonitorProperties properties, MonitorWorld world) {
        this.probes = probes;
        this.recordMapper = recordMapper;
        this.targetMapper = targetMapper;
        this.alertService = alertService;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.world = world;
    }

    /**
     * 执行一次采集（同步，最长探测超时时间），返回探测结果。
     * 在线：全程以目标归属租户为上下文（平台目标落平台库，租户目标切绑定库）；
     * 离线：runIgnore 直连本地离线库（单一监控世界，不路由平台库）。
     */
    public ProbeResult collect(MonTarget target) {
        if (world.offline()) {
            return TenantContext.runIgnore(() -> doCollect(target));
        }
        return TenantContext.runAs(target.getTenantId(), () -> doCollect(target));
    }

    private ProbeResult doCollect(MonTarget target) {
        Probe probe = probes.stream()
                .filter(p -> p.supports(target.getTargetType()))
                .findFirst()
                .orElse(null);
        ProbeResult result;
        if (probe == null) {
            result = ProbeResult.down("无支持类型 " + target.getTargetType() + " 的探测器");
        } else {
            try {
                result = probe.probe(target, properties.getProbe().getTimeoutMs());
            } catch (Exception e) {
                result = ProbeResult.down(SimpleHttp.abbreviate(e.getMessage()));
            }
        }
        persist(target, result);
        try {
            alertService.evaluate(target, result);
        } catch (Exception e) {
            // 告警评估失败不影响采集本身
        }
        return result;
    }

    private void persist(MonTarget target, ProbeResult result) {
        MonRecord record = new MonRecord();
        record.setTargetId(target.getId());
        record.setCollectTime(LocalDateTime.now().withNano(0));
        record.setStatus(result.getStatus());
        record.setLatencyMs(result.getLatencyMs());
        try {
            record.setMetrics(jsonMapper.writeValueAsString(
                    result.getMetrics() == null ? Map.of() : result.getMetrics()));
        } catch (Exception e) {
            record.setMetrics("{}");
        }
        recordMapper.insert(record);

        MonTarget patch = new MonTarget();
        patch.setId(target.getId());
        patch.setStatus(result.getStatus());
        patch.setStatusTime(record.getCollectTime());
        patch.setLastLatencyMs(result.getLatencyMs());
        targetMapper.updateById(patch);

        target.setStatus(result.getStatus());
        target.setStatusTime(record.getCollectTime());
        target.setLastLatencyMs(result.getLatencyMs());
    }
}
