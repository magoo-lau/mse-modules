package vip.mseapp.im.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.im.entity.ImMsgAudit;
import vip.mseapp.im.mapper.ImMsgAuditMapper;

/**
 * 消息外发审计：webhook afterSend 类事件全量异步落库（机器人链路之外），
 * 经回调分发线程执行（失败仅记日志，不影响回调应答）。
 */
@Service
public class ImMsgAuditService {

    private static final int TEXT_MAX = 1000;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ImMsgAuditMapper auditMapper;

    public ImMsgAuditService(ImMsgAuditMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    public void record(Long tenantId, JsonNode body) {
        try {
            TenantContext.runAs(tenantId, () -> {
                ImMsgAudit audit = new ImMsgAudit();
                audit.setTenantId(tenantId);
                audit.setMsgId(body.path("clientMsgID").asText(""));
                audit.setSendId(body.path("sendID").asText(""));
                audit.setRecvId(textOrNull(body.path("recvID").asText("")));
                audit.setGroupId(textOrNull(body.path("groupID").asText("")));
                audit.setSessionType(body.path("sessionType").asInt(1));
                audit.setContentType(body.path("contentType").asInt(0));
                audit.setContentText(truncate(extractText(body)));
                audit.setSenderNickname(textOrNull(body.path("sendNickname").asText("")));
                auditMapper.insert(audit);
                return null;
            });
        } catch (Exception ignored) {
            // 重复消息（uk 冲突）或库异常：审计尽力而为，不阻断回调链路
        }
    }

    /**
     * 消息元素 JSON 串 → 文本摘要：按 contentType 提取可读摘要（与客户端 chat/summary.ts 口径一致）。
     * 101 文本 / 106 @消息取正文（@全员标记还原为「@所有人」），114 引用取正文并附被引用消息摘要
     * （机器人上下文需感知引用语义），媒体与卡片类取标签+关键属性，110 custom 解协议
     * （link-card 取标题、通话信令取标记，其余原样），未知类型透传原文截断。
     */
    static String extractText(JsonNode body) {
        String raw = body.path("content").asText("");
        if (raw.isBlank()) {
            return "";
        }
        try {
            JsonNode elem = MAPPER.readTree(raw);
            return switch (body.path("contentType").asInt(0)) {
                case 101 -> elem.path("content").asText("");
                case 106 -> atAllDisplay(elem.path("text").asText(""));
                case 114 -> quoteSummary(elem);
                case 102 -> "[图片]";
                case 103 -> "[语音] " + Math.max(1, elem.path("duration").asInt(1)) + "s";
                case 104 -> "[视频]";
                case 105 -> ("[文件] " + elem.path("fileName").asText("")).trim();
                case 107 -> "[聊天记录]";
                case 108 -> ("[名片] " + elem.path("nickname").asText(elem.path("userID").asText(""))).trim();
                case 109 -> ("[位置] " + elem.path("description").asText("")).trim();
                case 110 -> customSummary(elem.path("data").asText(""));
                default -> raw;
            };
        } catch (Exception e) {
            return raw;
        }
    }

    /** @全员标记还原（服务端标记串字面量 atAllTag，大小写不敏感——与客户端 chat/util.ts 口径一致） */
    private static String atAllDisplay(String text) {
        return text.replaceAll("(?i)atalltag", "@所有人");
    }

    /**
     * 引用消息（114）摘要：正文 + 「[回复 引用者: 被引用消息摘要]」——被引用内容按其 contentType
     * 一层简化提取（content 内层为 JSON 串，101→content / 106/114→text，媒体→标签），摘要截断防引用链嵌套膨胀。
     */
    private static String quoteSummary(JsonNode elem) {
        String text = elem.path("text").asText("");
        JsonNode quote = elem.path("quoteMessage");
        if (!quote.isObject()) {
            return text;
        }
        String who = quote.path("senderNickname").asText(quote.path("sendID").asText(""));
        String summary = quotedText(quote);
        if (who.isBlank() && summary.isBlank()) {
            return text;
        }
        return (text + " [回复 " + who + ": " + summary + "]").trim();
    }

    /** 被引用消息（quoteMessage 对象）按 contentType 取一层摘要 */
    private static String quotedText(JsonNode quote) {
        String inner = quote.path("content").asText("");
        String summary = switch (quote.path("contentType").asInt(0)) {
            case 101 -> innerJsonText(inner, "content");
            case 106, 114 -> innerJsonText(inner, "text");
            case 102 -> "[图片]";
            case 103 -> "[语音]";
            case 104 -> "[视频]";
            case 105 -> "[文件] " + innerJsonText(inner, "fileName");
            case 107 -> "[聊天记录]";
            case 108 -> "[名片]";
            case 109 -> "[位置]";
            default -> inner.isBlank() ? "" : inner;
        };
        return truncate(summary == null ? "" : summary, 100);
    }

    /** 内层元素 JSON 串（如 {"content":".."}）取指定字段 */
    private static String innerJsonText(String inner, String field) {
        if (inner.isBlank()) {
            return "";
        }
        try {
            return MAPPER.readTree(inner).path(field).asText("");
        } catch (Exception e) {
            return inner;
        }
    }

    /** custom 消息（110）摘要：link-card/app-share 协议取标题，通话信令打标，其余原样返回 */
    private static String customSummary(String data) {
        if (data.isBlank()) {
            return "";
        }
        try {
            JsonNode v = new tools.jackson.databind.json.JsonMapper().readTree(data);
            String customType = v.path("customType").asText("");
            if ("link-card".equals(customType)) {
                return ("[链接] " + v.path("title").asText("")).trim();
            }
            if ("app-share".equals(customType)) {
                return ("[页面分享] " + v.path("title").asText("")).trim();
            }
            if ("call".equals(customType)) {
                return "[通话] " + ("video".equals(v.path("media").asText("")) ? "视频" : "语音") + "通话信令";
            }
            return data;
        } catch (Exception e) {
            return data;
        }
    }

    private static String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String truncate(String text) {
        return truncate(text, TEXT_MAX);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
