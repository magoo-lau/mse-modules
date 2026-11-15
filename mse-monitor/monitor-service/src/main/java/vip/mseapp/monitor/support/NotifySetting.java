package vip.mseapp.monitor.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 告警通知设置（mon_setting key=notify 的 JSON 视图，监控中心界面维护）。
 */
public class NotifySetting {

    /** 站内消息开关 */
    private boolean inboxEnabled = true;

    /** 站内消息收件人（基座用户 id + 展示名） */
    private List<UserItem> inboxUsers = new ArrayList<>();

    /** Webhook 机器人开关 */
    private boolean webhookEnabled = false;

    /** Webhook 地址（钉钉/企微/飞书群机器人） */
    private String webhookUrl = "";

    /** Webhook 报文格式：dingtalk/wecom/feishu */
    private String webhookFormat = "dingtalk";

    /** 持续触发重复通知间隔（分钟，0=只通知首次） */
    private int repeatNotifyMinutes = 0;

    /** 收件人条目：基座用户 id + 展示名（username/昵称） */
    public static class UserItem {
        private Long id;
        private String name;

        public UserItem() {
        }

        public UserItem(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public boolean isInboxEnabled() {
        return inboxEnabled;
    }

    public void setInboxEnabled(boolean inboxEnabled) {
        this.inboxEnabled = inboxEnabled;
    }

    public List<UserItem> getInboxUsers() {
        return inboxUsers;
    }

    public void setInboxUsers(List<UserItem> inboxUsers) {
        this.inboxUsers = inboxUsers;
    }

    public boolean isWebhookEnabled() {
        return webhookEnabled;
    }

    public void setWebhookEnabled(boolean webhookEnabled) {
        this.webhookEnabled = webhookEnabled;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getWebhookFormat() {
        return webhookFormat;
    }

    public void setWebhookFormat(String webhookFormat) {
        this.webhookFormat = webhookFormat;
    }

    public int getRepeatNotifyMinutes() {
        return repeatNotifyMinutes;
    }

    public void setRepeatNotifyMinutes(int repeatNotifyMinutes) {
        this.repeatNotifyMinutes = repeatNotifyMinutes;
    }
}
