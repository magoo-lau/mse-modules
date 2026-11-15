package vip.mseapp.im.notify;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.service.ImContactService;
import vip.mseapp.im.support.ImInstanceResolver;
import vip.mseapp.im.support.ImWebBaseResolver;
import vip.mseapp.api.notify.RemoteNotifyChannel;
import vip.mseapp.api.notify.dto.NotifyChannelDescriptor;
import vip.mseapp.api.notify.dto.NotifyMessage;

/**
 * IM 通知渠道提供方（向框架通知中枢自注册）：系统通知机器人（bot_notify）向接收人
 * 单聊代发——有跳转地址发 app-share 卡片（custom 110，客户端 chat/custom.ts 协议）：
 * linkUrl 为基座内相对路径（/sub/...，推荐）时以租户 webBase（台账行 > 全局配置）补全
 * 绝对地址，收件人点击经一次性 code 免登直达基座页面（与工作台/页面分享同链路）；已是
 * 绝对 http(s) 地址原样透传。无跳转地址发纯文本（101）；接收人未注册 IM 时先经账号桥
 * 补注册（ensureUsers）。
 *
 * <p>接入即生效：微服务形态由 im-boot 以 group=应用名导出本门面 + api-notify 推送器把渠道描述
 * 周期推送至基座 mse-system 登记（授权制渠道，feature.im.notify 权益开关由
 * V20261015_01_im__notify_entitlement.sql 平台域种子登记，平台按租户授权后可用——对整租户生效，
 * 不论哪个业务发送）；单体聚合形态框架中枢直接收集容器内本 Bean。
 */
@Component
public class ImNotifyChannelFacade implements RemoteNotifyChannel {

    private static final Logger log = LoggerFactory.getLogger(ImNotifyChannelFacade.class);

    /** 渠道编码 */
    public static final String CHANNEL_CODE = "im";

    /** 授权权益开关编码（平台域 sys_entitlement_item 种子项，默认 false 未授权） */
    public static final String ENTITLEMENT_CODE = "feature.im.notify";

    /** 系统通知机器人账号（bot_ 前缀普通用户，首次投递幂等注册进实例） */
    static final String NOTIFY_BOT_ID = "bot_notify";

    private static final String NOTIFY_BOT_NAME = "系统通知";

    /** OpenIM contentType：101 文本 / 110 custom；sessionType：1 单聊 */
    private static final int SESSION_SINGLE = 1;
    private static final int CONTENT_TEXT = 101;
    private static final int CONTENT_CUSTOM = 110;
    private static final String CUSTOM_TYPE_APP_SHARE = "app-share";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final ImContactService contactService;
    private final ImWebBaseResolver webBaseResolver;

    public ImNotifyChannelFacade(ImInstanceResolver instanceResolver, OpenImClient openImClient,
                                 ImContactService contactService, ImWebBaseResolver webBaseResolver) {
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.contactService = contactService;
        this.webBaseResolver = webBaseResolver;
    }

    @Override
    public List<NotifyChannelDescriptor> describeChannels() {
        return List.of(new NotifyChannelDescriptor(CHANNEL_CODE, "IM 消息",
                "OpenIM 单聊推送（系统通知机器人发送；有跳转地址渲染免登直达卡片，否则纯文本）",
                true, ENTITLEMENT_CODE));
    }

    @Override
    public boolean available(String channelCode) {
        if (!CHANNEL_CODE.equals(channelCode)) {
            return false;
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId == 0L) {
            return false; // 平台账号不参与 IM
        }
        try {
            instanceResolver.resolve(tenantId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void send(String channelCode, NotifyMessage message) {
        if (!CHANNEL_CODE.equals(channelCode)) {
            throw new ServiceException(400, "未知渠道编码：" + channelCode);
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || tenantId == 0L) {
            throw new ServiceException(403, "平台账号不参与 IM");
        }
        LinkedHashSet<Long> receivers = new LinkedHashSet<>();
        if (message.getReceiverIds() != null) {
            receivers.addAll(message.getReceiverIds());
        }
        if (receivers.isEmpty()) {
            return;
        }
        MseImProperties.Server server = instanceResolver.resolve(tenantId);
        String adminToken = openImClient.adminToken(server);
        ensureNotifyBot(server, adminToken);
        contactService.ensureUsers(List.copyOf(receivers));
        boolean card = message.getLinkUrl() != null && !message.getLinkUrl().isBlank();
        Map<String, Object> customContent = card ? cardContent(message, tenantId) : null;
        String plainText = card ? null : plainText(message);
        int sent = 0;
        Exception last = null;
        for (Long recvId : receivers) {
            try {
                if (card) {
                    openImClient.sendMsg(server, adminToken, NOTIFY_BOT_ID, String.valueOf(recvId), null,
                            SESSION_SINGLE, CONTENT_CUSTOM, customContent, NOTIFY_BOT_NAME, null);
                } else {
                    openImClient.sendMsg(server, adminToken, NOTIFY_BOT_ID, String.valueOf(recvId), null,
                            SESSION_SINGLE, CONTENT_TEXT, Map.of("content", plainText), NOTIFY_BOT_NAME, null);
                }
                sent++;
            } catch (Exception e) {
                last = e;
                log.warn("[mse-im] 通知投递单人失败（续发其余接收人）: recv={}, {} - {}", recvId,
                        message.getBizCode(), e.getMessage());
            }
        }
        if (sent == 0 && last != null) {
            throw new ServiceException(502, "IM 渠道投递全部失败：" + last.getMessage());
        }
    }

    /** 系统通知机器人幂等注册（与 ImBotService 保存注册同语义：未注册则注册，已注册刷新资料） */
    private void ensureNotifyBot(MseImProperties.Server server, String adminToken) {
        if (!openImClient.userRegisterIfAbsent(server, adminToken, NOTIFY_BOT_ID, NOTIFY_BOT_NAME, null)) {
            openImClient.updateUserProfile(server, adminToken, NOTIFY_BOT_ID, NOTIFY_BOT_NAME, null);
        }
    }

    /** app-share 卡片 payload（customElem.data 的 JSON，与客户端 chat/custom.ts、ImShareService 同构；
     *  icon 空=客户端回退默认图标） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AppShareCard(String customType, String title, String desc, String url, String icon) {
    }

    private Map<String, Object> cardContent(NotifyMessage message, Long tenantId) {
        String title = message.getTitle() == null || message.getTitle().isBlank() ? "通知" : message.getTitle();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("data", MAPPER.writeValueAsString(
                new AppShareCard(CUSTOM_TYPE_APP_SHARE, title, message.getContent(),
                        absoluteUrl(message.getLinkUrl(), tenantId), null)));
        content.put("extension", "");
        content.put("description", title);
        return content;
    }

    /** 跳转地址补全：基座内相对路径（/sub/...）结合租户 webBase 拼绝对地址（app-share 点击按
     *  同源比对 webBase 决定是否签发免登 code，故卡片 url 必须落在 webBase 同站点）；
     *  已是绝对 http(s) 地址原样透传 */
    private String absoluteUrl(String linkUrl, Long tenantId) {
        if (linkUrl.matches("(?i)^https?://.*")) {
            return linkUrl;
        }
        String base = webBaseResolver.resolve(tenantId);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return linkUrl.startsWith("/") ? base + linkUrl : base + "/" + linkUrl;
    }

    private static String plainText(NotifyMessage message) {
        String title = message.getTitle() == null ? "" : message.getTitle();
        String body = message.getContent() == null ? "" : message.getContent();
        return title.isBlank() ? body : title + "\n" + body;
    }
}
