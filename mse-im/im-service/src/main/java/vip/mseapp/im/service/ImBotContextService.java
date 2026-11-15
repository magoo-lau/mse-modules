package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImBot;
import vip.mseapp.im.entity.ImMsgAudit;
import vip.mseapp.im.mapper.ImMsgAuditMapper;
import vip.mseapp.im.support.ImInstanceResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 机器人提问上下文组装（审计表注入式）：触发后从 im_msg_audit 消息存档拉本会话最近
 * N 条（群聊=group_id；单聊=与提问者的双向消息流），渲染为「昵称: 文本」脚本连同
 * 净化后的当前提问拼成给智能体的 message。
 *
 * <p>刻意不传 conversationId（无 AI 侧会话状态）：机器人自己的历史回复同样经 webhook
 * 落存档，天然多轮、重启无损，且能感知群成员之间的对话（AI 侧记忆只覆盖「人↔bot」问答对，
 * 覆盖不了群聊语境）；也避免每轮把拼好的长上下文重复写进 ai_chat_message 灌满记忆窗口。
 */
@Service
public class ImBotContextService {

    private static final Logger log = LoggerFactory.getLogger(ImBotContextService.class);

    private final ImMsgAuditMapper auditMapper;
    private final OpenImClient openImClient;
    private final ImInstanceResolver instanceResolver;
    private final MseImProperties properties;

    public ImBotContextService(ImMsgAuditMapper auditMapper, OpenImClient openImClient,
                               ImInstanceResolver instanceResolver, MseImProperties properties) {
        this.auditMapper = auditMapper;
        this.openImClient = openImClient;
        this.instanceResolver = instanceResolver;
        this.properties = properties;
    }

    /**
     * 组装最终 message：场景说明（群聊带群名，失败静默降级）→ 聊天记录脚本（时间正序，
     * 含机器人此前回复，最后一条为净化后的当前提问）→ 回答要求。上下文条数/字符双限
     * （mse.im.bot.context-size / context-max-chars），超限从最旧一条开始裁剪。
     */
    public String buildPrompt(Long tenantId, ImBot bot, boolean group, String groupID,
                              String askerId, String askerNickname, String currentMsgId, String question) {
        String askerName = displayName(askerId, askerNickname, bot);
        String questionLine = askerName + ": " + sanitizeQuestion(question, bot.getNickname());

        int size = Math.max(0, properties.getBot().getContextSize());
        List<String> lines = new ArrayList<>();
        if (size > 0) {
            for (ImMsgAudit m : loadHistory(tenantId, group, groupID, bot.getBotUserId(), askerId, currentMsgId, size)) {
                lines.add(displayName(m.getSendId(), m.getSenderNickname(), bot) + ": "
                        + (m.getContentText() == null ? "" : m.getContentText()));
            }
        }
        trimToBudget(lines, questionLine);

        StringBuilder prompt = new StringBuilder();
        if (group) {
            String groupName = resolveGroupName(tenantId, groupID);
            prompt.append(groupName == null || groupName.isBlank()
                    ? "你是 IM 群聊中的 AI 助手「" + bot.getNickname() + "」。"
                    : "你是 IM 群聊「" + groupName + "」中的 AI 助手「" + bot.getNickname() + "」。");
            prompt.append("以下是本群最近的聊天记录（按时间正序，最后一条是当前提问），")
                    .append("其中「").append(bot.getNickname()).append("」的发言是你此前的回复。\n");
        } else {
            prompt.append("你是 IM 私聊中的 AI 助手「").append(bot.getNickname()).append("」，正在与「")
                    .append(askerName).append("」对话。以下是最近的聊天记录（按时间正序，最后一条是当前提问），")
                    .append("其中「").append(bot.getNickname()).append("」的发言是你此前的回复。\n");
        }
        prompt.append("——— 聊天记录开始 ———\n");
        for (String line : lines) {
            prompt.append(line).append('\n');
        }
        prompt.append(questionLine).append('\n');
        prompt.append("——— 聊天记录结束 ———\n");
        prompt.append("请结合以上聊天记录回答最后一条提问。用中文回答，简洁自然，不要复述聊天记录，也不要提及本说明。");
        return prompt.toString();
    }

    /** 会话历史：群聊按 group 倒序取再正序化；单聊两个方向各取后合并按 id 排序取末段；均排除当前触发消息 */
    private List<ImMsgAudit> loadHistory(Long tenantId, boolean group, String groupID,
                                         String botUserId, String askerId, String excludeMsgId, int size) {
        if (group) {
            LambdaQueryWrapper<ImMsgAudit> wrapper = new LambdaQueryWrapper<ImMsgAudit>()
                    .eq(ImMsgAudit::getTenantId, tenantId)
                    .eq(ImMsgAudit::getSessionType, 3)
                    .eq(ImMsgAudit::getGroupId, groupID)
                    .orderByDesc(ImMsgAudit::getId)
                    .last("limit " + size);
            if (excludeMsgId != null && !excludeMsgId.isBlank()) {
                wrapper.ne(ImMsgAudit::getMsgId, excludeMsgId);
            }
            List<ImMsgAudit> asc = new ArrayList<>(auditMapper.selectList(wrapper));
            java.util.Collections.reverse(asc);
            return asc;
        }
        List<ImMsgAudit> merged = new ArrayList<>(pairMessages(tenantId, askerId, botUserId, excludeMsgId, size));
        merged.addAll(pairMessages(tenantId, botUserId, askerId, excludeMsgId, size));
        merged.sort(Comparator.comparingLong(ImMsgAudit::getId));
        return tail(merged, size);
    }

    private List<ImMsgAudit> pairMessages(Long tenantId, String sendId, String recvId, String excludeMsgId, int size) {
        LambdaQueryWrapper<ImMsgAudit> wrapper = new LambdaQueryWrapper<ImMsgAudit>()
                .eq(ImMsgAudit::getTenantId, tenantId)
                .eq(ImMsgAudit::getSessionType, 1)
                .eq(ImMsgAudit::getSendId, sendId)
                .eq(ImMsgAudit::getRecvId, recvId)
                .orderByDesc(ImMsgAudit::getId)
                .last("limit " + size);
        if (excludeMsgId != null && !excludeMsgId.isBlank()) {
            wrapper.ne(ImMsgAudit::getMsgId, excludeMsgId);
        }
        return auditMapper.selectList(wrapper);
    }

    private static List<ImMsgAudit> tail(List<ImMsgAudit> list, int size) {
        return list.size() > size ? new ArrayList<>(list.subList(list.size() - size, list.size())) : list;
    }

    /** 总字符超 context-max-chars 时从最旧一条开始裁（当前提问与结尾要求永远保留，历史至少留 1 条） */
    private void trimToBudget(List<String> lines, String questionLine) {
        int maxChars = properties.getBot().getContextMaxChars();
        int kept = questionLine.length();
        for (String line : lines) {
            kept += line.length() + 1;
        }
        while (lines.size() > 1 && kept > maxChars) {
            kept -= lines.remove(0).length() + 1;
        }
    }

    /** 展示名：昵称快照优先；缺失时当前 bot 回落其配置昵称，其余回落 userID（历史行昵称为空的兜底） */
    private static String displayName(String sendId, String nickname, ImBot bot) {
        if (nickname != null && !nickname.isBlank()) {
            return nickname;
        }
        if (bot != null && bot.getBotUserId() != null && bot.getBotUserId().equals(sendId)) {
            return bot.getNickname() == null || bot.getNickname().isBlank() ? sendId : bot.getNickname();
        }
        return sendId == null || sendId.isBlank() ? "未知用户" : sendId;
    }

    /** 当前提问净化：去开头的「@机器人昵称」引导前缀（atAllTag 已在摘要层还原），净化后为空则回退原文 */
    private static String sanitizeQuestion(String question, String botNickname) {
        String text = question == null ? "" : question.trim();
        if (text.isBlank()) {
            return text;
        }
        if (botNickname != null && botNickname.trim().length() >= 2) {
            String sanitized = text.replaceAll("^@" + java.util.regex.Pattern.quote(botNickname.trim()) + "\\s*", "");
            if (!sanitized.isBlank()) {
                return sanitized;
            }
        }
        return text;
    }

    /** 群名（仅增强场景语义，实例不可达等失败一律静默降级为不带群名） */
    private String resolveGroupName(Long tenantId, String groupID) {
        if (groupID == null || groupID.isBlank()) {
            return null;
        }
        try {
            MseImProperties.Server server = instanceResolver.resolve(tenantId);
            OpenImClient.GroupInfo info = openImClient.getGroupInfo(server, openImClient.adminToken(server), groupID);
            return info == null ? null : info.groupName();
        } catch (Exception e) {
            log.debug("[mse-im] 机器人上下文群名获取失败（降级不带群名）: group={} err={}", groupID, e.getMessage());
            return null;
        }
    }
}
