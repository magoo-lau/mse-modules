package vip.mseapp.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.monitor.engine.probe.ProbeResult;
import vip.mseapp.monitor.entity.MonAlert;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.mapper.MonAlertMapper;
import vip.mseapp.monitor.support.AlertConfig;
import vip.mseapp.monitor.support.MonitorWorld;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 告警状态机：同一目标同一规则 (target_id, rule_key) 至多一条 firing。
 *
 * <p>触发（新违例且无 firing）→ 新建 + 通知；持续触发 → 按 repeat_notify_minutes 周期重发
 * （0=只通知首次）；违例消失或手动处理 → 转 resolved + 恢复通知。通知结果写 notify_result。
 * 评估在采集上下文内执行（在线=目标归属库，离线=本地离线库）；手动处理按世界边界 scope。
 */
@Service
public class AlertService {

    private final MonAlertMapper alertMapper;
    private final NotifyService notifyService;
    private final NotifySettingService notifySettingService;
    private final JsonMapper jsonMapper;
    private final MonitorWorld world;

    public AlertService(MonAlertMapper alertMapper, NotifyService notifyService,
                        NotifySettingService notifySettingService, JsonMapper jsonMapper,
                        MonitorWorld world) {
        this.alertMapper = alertMapper;
        this.notifyService = notifyService;
        this.notifySettingService = notifySettingService;
        this.jsonMapper = jsonMapper;
        this.world = world;
    }

    /** 每次采集后评估：违例集合 vs firing 集合做差分收敛 */
    public void evaluate(MonTarget target, ProbeResult result) {
        AlertConfig cfg = AlertConfig.parse(jsonMapper, target.getAlertConfig());
        Map<String, Object> metrics = result.getMetrics() == null ? Map.of() : result.getMetrics();

        Map<String, String> violated = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(cfg.getDown()) && "down".equals(result.getStatus())) {
            violated.put(MonAlert.RULE_DOWN, "目标「" + target.getName() + "」探测失败："
                    + (result.getError() == null || result.getError().isBlank() ? "未知原因" : result.getError()));
        }
        if (cfg.getLatencyMs() != null && result.getLatencyMs() != null
                && result.getLatencyMs() > cfg.getLatencyMs()) {
            violated.put(MonAlert.RULE_LATENCY, "目标「" + target.getName() + "」响应耗时 "
                    + result.getLatencyMs() + "ms，超过阈值 " + cfg.getLatencyMs() + "ms");
        }
        putNumeric(violated, MonAlert.RULE_CPU, target.getName(), metrics, "cpu_usage_pct",
                cfg.getCpuPercent(), "CPU 使用率", "%");
        putNumeric(violated, MonAlert.RULE_MEM, target.getName(), metrics, "mem_usage_pct",
                cfg.getMemPercent(), "内存使用率", "%");
        putNumeric(violated, MonAlert.RULE_DISK, target.getName(), metrics, "disk_usage_pct",
                cfg.getDiskPercent(), "磁盘使用率", "%");
        putNumeric(violated, MonAlert.RULE_REDIS_MEM, target.getName(), metrics, "mem_usage_pct",
                cfg.getRedisMemPercent(), "Redis 内存使用率", "%");
        if (cfg.getMysqlMaxThreads() != null && metrics.get("threads_connected") instanceof Number threads
                && threads.longValue() > cfg.getMysqlMaxThreads()) {
            violated.put(MonAlert.RULE_MYSQL_THREADS, "MySQL「" + target.getName() + "」连接数 "
                    + threads.longValue() + "，超过阈值 " + cfg.getMysqlMaxThreads());
        }
        putNumeric(violated, MonAlert.RULE_JVM_HEAP, target.getName(), metrics, "heap_usage_pct",
                cfg.getJvmHeapPercent(), "JVM 堆内存使用率", "%");
        if (cfg.getSlowSqlCount() != null) {
            putNumeric(violated, MonAlert.RULE_SLOW_SQL, target.getName(), metrics, "slowsql_rate",
                    cfg.getSlowSqlCount().doubleValue(), "慢SQL新增", " 条");
        }
        if (cfg.getRedisSlowCmdCount() != null && "是".equals(metrics.get("slowcmd_capturing"))
                && metrics.get("slow_cmds") instanceof String slowCmds) {
            int count = countSlowCmds(slowCmds);
            if (count >= cfg.getRedisSlowCmdCount()) {
                violated.put(MonAlert.RULE_REDIS_SLOW_CMD, "Redis「" + target.getName() + "」本轮抓到慢命令 "
                        + count + " 条，达到阈值 " + cfg.getRedisSlowCmdCount() + "（慢命令抓取开启中）");
            }
        }

        List<MonAlert> firing = alertMapper.selectList(
                new LambdaQueryWrapper<MonAlert>()
                        .eq(MonAlert::getTargetId, target.getId())
                        .eq(MonAlert::getStatus, MonAlert.STATUS_FIRING));
        Set<String> firingKeys = firing.stream()
                .map(MonAlert::getRuleKey).collect(Collectors.toSet());

        LocalDateTime now = LocalDateTime.now().withNano(0);
        long repeatMs = notifySettingService.current().getRepeatNotifyMinutes() * 60_000L;
        for (Map.Entry<String, String> entry : violated.entrySet()) {
            MonAlert existing = firing.stream()
                    .filter(a -> entry.getKey().equals(a.getRuleKey())).findFirst().orElse(null);
            if (existing == null) {
                fire(target, entry.getKey(), entry.getValue(), now);
            } else if (repeatMs > 0 && existing.getLastTriggerTime() != null
                    && Duration.between(existing.getLastTriggerTime(), now).toMillis() >= repeatMs) {
                existing.setContent(entry.getValue());
                existing.setLastTriggerTime(now);
                existing.setNotifyResult(jsonMapper.writeValueAsString(
                        notifyService.deliver(existing, false)));
                alertMapper.updateById(existing);
            }
        }
        for (MonAlert alert : firing) {
            if (!violated.containsKey(alert.getRuleKey())) {
                resolve(alert, now, false);
            }
        }
    }

    /** 手动处理：firing 立即转 resolved + 恢复通知 */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long id) {
        MonAlert alert = world.scope(() -> alertMapper.selectById(id));
        if (alert == null) {
            throw new ServiceException(404, "告警不存在");
        }
        if (MonAlert.STATUS_FIRING.equals(alert.getStatus())) {
            resolve(alert, LocalDateTime.now().withNano(0), true);
        }
    }

    private void fire(MonTarget target, String ruleKey, String content, LocalDateTime now) {
        MonAlert alert = new MonAlert();
        alert.setTargetId(target.getId());
        alert.setTargetName(target.getName());
        alert.setLevel(MonAlert.RULE_DOWN.equals(ruleKey)
                ? MonAlert.LEVEL_ERROR : MonAlert.LEVEL_WARN);
        alert.setRuleKey(ruleKey);
        alert.setContent(content);
        alert.setStatus(MonAlert.STATUS_FIRING);
        alert.setFirstTriggerTime(now);
        alert.setLastTriggerTime(now);
        alert.setNotifyResult(jsonMapper.writeValueAsString(notifyService.deliver(alert, false)));
        alertMapper.insert(alert);
    }

    private void resolve(MonAlert alert, LocalDateTime now, boolean manual) {
        alert.setStatus(MonAlert.STATUS_RESOLVED);
        alert.setResolveTime(now);
        if (manual) {
            alert.setContent(alert.getContent() + "（人工处理）");
        }
        alert.setNotifyResult(jsonMapper.writeValueAsString(notifyService.deliver(alert, true)));
        alertMapper.updateById(alert);
    }

    private void putNumeric(Map<String, String> violated, String ruleKey, String targetName,
                            Map<String, Object> metrics, String metricKey, Double threshold,
                            String label, String unit) {
        if (threshold == null || !(metrics.get(metricKey) instanceof Number value)) {
            return;
        }
        if (value.doubleValue() > threshold) {
            violated.put(ruleKey, "目标「" + targetName + "」" + label + " " + value.doubleValue()
                    + unit + "，超过阈值 " + threshold + unit);
        }
    }

    /** 本轮抓到的慢命令条数（slow_cmds JSON 数组长度，解析失败按 0 不告警） */
    private int countSlowCmds(String slowCmds) {
        try {
            List<?> list = jsonMapper.readValue(slowCmds, List.class);
            return list == null ? 0 : list.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
