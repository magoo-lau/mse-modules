package vip.mseapp.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import vip.mseapp.monitor.entity.MonAlert;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.mapper.MonAlertMapper;
import vip.mseapp.monitor.mapper.MonTargetMapper;
import vip.mseapp.monitor.support.MonitorWorld;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 监控总览：状态统计 + 目标卡片 + 最近告警。
 * 在线按会话上下文隔离（平台看平台自身，租户看本租户）；离线为本地单一监控世界（本地离线库全量）。
 */
@Service
public class DashboardService {

    private final MonTargetMapper targetMapper;
    private final MonAlertMapper alertMapper;
    private final MonitorWorld world;

    public DashboardService(MonTargetMapper targetMapper, MonAlertMapper alertMapper, MonitorWorld world) {
        this.targetMapper = targetMapper;
        this.alertMapper = alertMapper;
        this.world = world;
    }

    public Map<String, Object> summary() {
        List<MonTarget> targets = world.scope(() -> targetMapper.selectList(
                new LambdaQueryWrapper<MonTarget>()
                        .orderByAsc(MonTarget::getTargetType)
                        .orderByAsc(MonTarget::getName)));
        targets.forEach(t -> {
            if (t.getPassword() != null && !t.getPassword().isBlank()) {
                t.setPassword(TargetService.PASSWORD_MASK);
            }
        });

        long up = 0;
        long down = 0;
        long unknown = 0;
        long disabled = 0;
        for (MonTarget target : targets) {
            if (target.getEnabled() != null && target.getEnabled() == MonTarget.DISABLED) {
                disabled++;
            } else if (MonTarget.STATUS_UP.equals(target.getStatus())) {
                up++;
            } else if (MonTarget.STATUS_DOWN.equals(target.getStatus())) {
                down++;
            } else {
                unknown++;
            }
        }
        Long firing = world.scope(() -> alertMapper.selectCount(
                new LambdaQueryWrapper<MonAlert>().eq(MonAlert::getStatus, MonAlert.STATUS_FIRING)));
        List<MonAlert> recentAlerts = world.scope(() -> alertMapper.selectList(
                new LambdaQueryWrapper<MonAlert>()
                        .orderByDesc(MonAlert::getLastTriggerTime)
                        .last("LIMIT 10")));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", (long) targets.size());
        summary.put("up", up);
        summary.put("down", down);
        summary.put("unknown", unknown);
        summary.put("disabled", disabled);
        summary.put("firingAlerts", firing == null ? 0 : firing);
        // 总览卡片网格只展示已启用目标，统计数字仍按全部目标计算
        summary.put("targets", targets.stream()
                .filter(t -> t.getEnabled() == null || t.getEnabled() != MonTarget.DISABLED)
                .toList());
        summary.put("recentAlerts", recentAlerts);
        return summary;
    }
}
