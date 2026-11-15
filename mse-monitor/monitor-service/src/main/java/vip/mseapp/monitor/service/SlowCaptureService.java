package vip.mseapp.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.monitor.entity.MonSetting;
import vip.mseapp.monitor.mapper.MonSettingMapper;
import vip.mseapp.monitor.support.MonitorWorld;
import vip.mseapp.monitor.support.SlowCaptureSetting;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Redis 慢命令抓取开关：mon_setting key=redis-slowlog-&lt;targetId&gt; 行的读写（界面维护）。
 *
 * <p>抓取是排查工具：开启方式二选一——持续时长（minutes，到期自动停）或指定起止时间窗；
 * endAt 为空表示持续开启直到手动关。窗口过期读取时自然失效，无需清理任务。
 */
@Service
public class SlowCaptureService {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 持续时长上限（分钟）：抓取是临时排查手段，不允许无限期定时 */
    private static final int MAX_MINUTES = 24 * 60;

    private final MonSettingMapper settingMapper;
    private final JsonMapper jsonMapper;
    private final MonitorWorld world;

    public SlowCaptureService(MonSettingMapper settingMapper, JsonMapper jsonMapper, MonitorWorld world) {
        this.settingMapper = settingMapper;
        this.jsonMapper = jsonMapper;
        this.world = world;
    }

    /** 当前开关配置（无行/解析失败回退全关） */
    public SlowCaptureSetting get(Long targetId) {
        MonSetting row = world.scope(() -> settingMapper.selectOne(
                new LambdaQueryWrapper<MonSetting>().eq(MonSetting::getConfigKey, key(targetId))));
        if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return SlowCaptureSetting.disabled();
        }
        try {
            return jsonMapper.readValue(row.getConfigValue(), SlowCaptureSetting.class);
        } catch (Exception e) {
            return SlowCaptureSetting.disabled();
        }
    }

    /** 是否抓取中（enabled 且当前时间在窗口内） */
    public boolean capturing(Long targetId) {
        return get(targetId).capturingNow();
    }

    /**
     * 设置开关：开启时二选一——minutes（持续时长，自动计算窗口）或 startAt/endAt（指定起止）；
     * 关闭时保留窗口字段仅置 enabled=false，便于界面回显上次配置。
     */
    @Transactional(rollbackFor = Exception.class)
    public SlowCaptureSetting set(Long targetId, boolean enabled, Integer minutes, String startAt, String endAt) {
        SlowCaptureSetting setting = enabled ? new SlowCaptureSetting() : get(targetId);
        setting.setEnabled(enabled);
        if (enabled) {
            if (minutes != null && minutes > 0) {
                if (minutes > MAX_MINUTES) {
                    throw new ServiceException(400, "持续时长不能超过 1440 分钟");
                }
                LocalDateTime now = LocalDateTime.now().withNano(0);
                setting.setStartAt(now.format(FORMAT));
                setting.setEndAt(now.plusMinutes(minutes).format(FORMAT));
            } else {
                LocalDateTime start = parse(startAt, "开始时间");
                LocalDateTime end = parse(endAt, "结束时间");
                if (start == null || end == null) {
                    throw new ServiceException(400, "开启抓取须提供持续时长或起止时间");
                }
                if (!end.isAfter(start)) {
                    throw new ServiceException(400, "结束时间必须晚于开始时间");
                }
                setting.setStartAt(start.format(FORMAT));
                setting.setEndAt(end.format(FORMAT));
            }
        }
        String value = jsonMapper.writeValueAsString(setting);
        String configKey = key(targetId);
        MonSetting existing = world.scope(() -> settingMapper.selectOne(
                new LambdaQueryWrapper<MonSetting>().eq(MonSetting::getConfigKey, configKey)));
        if (existing == null) {
            MonSetting toInsert = new MonSetting();
            toInsert.setConfigKey(configKey);
            toInsert.setConfigValue(value);
            world.scope(() -> settingMapper.insert(toInsert));
            return setting;
        }
        existing.setConfigValue(value);
        world.scope(() -> settingMapper.updateById(existing));
        return setting;
    }

    private LocalDateTime parse(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), FORMAT);
        } catch (Exception e) {
            throw new ServiceException(400, label + "格式须为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private static String key(Long targetId) {
        return MonSetting.KEY_REDIS_SLOWLOG_PREFIX + targetId;
    }
}
