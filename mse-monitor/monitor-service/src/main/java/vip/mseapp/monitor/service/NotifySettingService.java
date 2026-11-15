package vip.mseapp.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.monitor.entity.MonSetting;
import vip.mseapp.monitor.mapper.MonSettingMapper;
import vip.mseapp.monitor.support.MonitorWorld;
import vip.mseapp.monitor.support.NotifySetting;

/**
 * 告警通知设置：mon_setting key=notify 行的读写（界面维护，每次告警实时读取）。
 * 读取发生在目标归属上下文内（在线=归属库；离线=本地离线库）。
 */
@Service
public class NotifySettingService {

    private final MonSettingMapper settingMapper;
    private final JsonMapper jsonMapper;
    private final MonitorWorld world;

    public NotifySettingService(MonSettingMapper settingMapper, JsonMapper jsonMapper, MonitorWorld world) {
        this.settingMapper = settingMapper;
        this.jsonMapper = jsonMapper;
        this.world = world;
    }

    /** 当前设置（无行/解析失败回退默认：仅站内信，无收件人） */
    public NotifySetting current() {
        MonSetting row = world.scope(() -> settingMapper.selectOne(
                new LambdaQueryWrapper<MonSetting>().eq(MonSetting::getConfigKey, MonSetting.KEY_NOTIFY)));
        if (row == null || row.getConfigValue() == null || row.getConfigValue().isBlank()) {
            return new NotifySetting();
        }
        try {
            return jsonMapper.readValue(row.getConfigValue(), NotifySetting.class);
        } catch (Exception e) {
            return new NotifySetting();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(NotifySetting setting) {
        validate(setting);
        String value = jsonMapper.writeValueAsString(setting);
        MonSetting existing = world.scope(() -> settingMapper.selectOne(
                new LambdaQueryWrapper<MonSetting>().eq(MonSetting::getConfigKey, MonSetting.KEY_NOTIFY)));
        if (existing == null) {
            MonSetting toInsert = new MonSetting();
            toInsert.setConfigKey(MonSetting.KEY_NOTIFY);
            toInsert.setConfigValue(value);
            world.scope(() -> settingMapper.insert(toInsert));
            return;
        }
        existing.setConfigValue(value);
        world.scope(() -> settingMapper.updateById(existing));
    }

    private void validate(NotifySetting setting) {
        if (setting.isInboxEnabled()
                && (setting.getInboxUsers() == null || setting.getInboxUsers().isEmpty())) {
            throw new ServiceException(400, "站内信收件人不能为空");
        }
        if (setting.isWebhookEnabled()) {
            String url = setting.getWebhookUrl();
            if (url == null || !url.trim().toLowerCase().startsWith("http")) {
                throw new ServiceException(400, "Webhook 地址必须以 http/https 开头");
            }
            String format = setting.getWebhookFormat();
            if (!"dingtalk".equals(format) && !"wecom".equals(format) && !"feishu".equals(format)) {
                throw new ServiceException(400, "Webhook 格式仅支持 dingtalk/wecom/feishu");
            }
        }
        if (setting.getRepeatNotifyMinutes() < 0 || setting.getRepeatNotifyMinutes() > 24 * 60) {
            throw new ServiceException(400, "重复通知间隔须在 0-1440 分钟之间");
        }
    }
}
