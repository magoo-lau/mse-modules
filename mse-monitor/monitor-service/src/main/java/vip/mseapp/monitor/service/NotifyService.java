package vip.mseapp.monitor.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.api.system.RemoteMessageService;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.monitor.support.NotifySetting;
import vip.mseapp.monitor.support.SimpleHttp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警通知投递：按「通知设置」配置分发——站内消息（api-system 契约，单体本地直调/微服务 Dubbo）
 * 与 Webhook 机器人（钉钉/企微/飞书 markdown 报文）。监控中心独立直发、不经框架通知中枢
 * （v3.1 退出：通知设置即投递开关，webhook 渠道已由框架内置、与监控中心配置互不相干）。
 *
 * <p>投递失败不抛出：结果记入 mon_alert.notify_result 供界面查看。
 */
@Service
public class NotifyService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> RULE_LABELS = Map.of(
            "down", "探测失败",
            "latency", "响应超时",
            "cpu", "CPU 使用率过高",
            "mem", "内存使用率过高",
            "disk", "磁盘使用率过高",
            "redis_mem", "Redis 内存过高",
            "mysql_threads", "MySQL 连接数过高",
            "jvm_heap", "JVM 堆内存过高",
            "slow_sql", "慢SQL 新增过多",
            "redis_slow_cmd", "Redis 慢命令过多");

    private final NotifySettingService settingService;
    private final JsonMapper jsonMapper;

    @RpcReference
    private RemoteMessageService messageService;

    public NotifyService(NotifySettingService settingService, JsonMapper jsonMapper) {
        this.settingService = settingService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 投递一条告警/恢复通知。
     *
     * @param alert    告警（content 为最新文案）
     * @param resolved true=恢复通知
     * @return 各通道投递结果（ok 或失败原因），空 map 表示无通道启用
     */
    public Map<String, Object> deliver(vip.mseapp.monitor.entity.MonAlert alert, boolean resolved) {
        NotifySetting setting = settingService.current();
        Map<String, Object> result = new LinkedHashMap<>();
        String title = (resolved ? "【已恢复】" : "【告警】") + alert.getTargetName()
                + "：" + RULE_LABELS.getOrDefault(alert.getRuleKey(), alert.getRuleKey());
        String content = alert.getContent() + "\n时间：" + LocalDateTime.now().format(TIME)
                + (resolved ? "\n状态：已恢复正常" : "\n状态：持续触发中");

        if (setting.isInboxEnabled()) {
            List<Long> receiverIds = setting.getInboxUsers() == null ? List.of()
                    : setting.getInboxUsers().stream()
                    .map(NotifySetting.UserItem::getId)
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .toList();
            if (receiverIds.isEmpty()) {
                result.put("inbox", "未配置有效收件人");
            } else {
                try {
                    messageService.send(receiverIds, "MONITOR", title, content,
                            String.valueOf(alert.getTargetId()));
                    result.put("inbox", "ok");
                } catch (Exception e) {
                    result.put("inbox", "失败：" + SimpleHttp.abbreviate(e.getMessage()));
                }
            }
        }

        if (setting.isWebhookEnabled() && setting.getWebhookUrl() != null
                && !setting.getWebhookUrl().isBlank()) {
            try {
                SimpleHttp.postJson(setting.getWebhookUrl(),
                        webhookBody(setting.getWebhookFormat(), title, content), 8_000);
                result.put("webhook", "ok");
            } catch (Exception e) {
                result.put("webhook", "失败：" + SimpleHttp.abbreviate(e.getMessage()));
            }
        }
        return result;
    }

    /** 群机器人报文：钉钉/企微 markdown，飞书 text（post 类型结构复杂，text 足够） */
    private String webhookBody(String format, String title, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (format == null ? "dingtalk" : format) {
            case "wecom" -> {
                Map<String, Object> markdown = new LinkedHashMap<>();
                markdown.put("content", "**" + title + "**\n" + content);
                body.put("msgtype", "markdown");
                body.put("markdown", markdown);
            }
            case "feishu" -> {
                Map<String, Object> inner = new LinkedHashMap<>();
                inner.put("text", title + "\n" + content);
                body.put("msg_type", "text");
                body.put("content", inner);
            }
            default -> {
                Map<String, Object> markdown = new LinkedHashMap<>();
                markdown.put("title", title);
                markdown.put("text", "#### " + title + "\n\n" + content.replace("\n", "\n\n"));
                body.put("msgtype", "markdown");
                body.put("markdown", markdown);
            }
        }
        return jsonMapper.writeValueAsString(body);
    }

    /** 供测试连通性：直接 POST 一条测试消息 */
    public Map<String, Object> sendTest() {
        NotifySetting setting = settingService.current();
        Map<String, Object> result = new LinkedHashMap<>();
        if (setting.isInboxEnabled()) {
            List<Long> receiverIds = setting.getInboxUsers() == null ? List.of()
                    : setting.getInboxUsers().stream()
                    .map(NotifySetting.UserItem::getId)
                    .filter(id -> id != null && id > 0)
                    .distinct()
                    .toList();
            if (receiverIds.isEmpty()) {
                result.put("inbox", "未配置有效收件人");
            } else {
                try {
                    messageService.send(receiverIds, "MONITOR", "【测试】监控中心通知连通性测试",
                            "这是一条监控中心发出的测试通知，收到即站内信通道配置正确。", null);
                    result.put("inbox", "ok");
                } catch (Exception e) {
                    result.put("inbox", "失败：" + SimpleHttp.abbreviate(e.getMessage()));
                }
            }
        }
        if (setting.isWebhookEnabled() && setting.getWebhookUrl() != null
                && !setting.getWebhookUrl().isBlank()) {
            try {
                SimpleHttp.postJson(setting.getWebhookUrl(),
                        webhookBody(setting.getWebhookFormat(), "【测试】监控中心通知连通性测试",
                                "这是一条监控中心发出的测试通知，收到即 Webhook 通道配置正确。"), 8_000);
                result.put("webhook", "ok");
            } catch (Exception e) {
                result.put("webhook", "失败：" + SimpleHttp.abbreviate(e.getMessage()));
            }
        }
        return result;
    }
}
