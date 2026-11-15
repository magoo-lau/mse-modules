package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImBot;
import vip.mseapp.im.mapper.ImBotMapper;
import vip.mseapp.im.support.ImInstanceResolver;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * IM 机器人管理：bot 即 OpenIM 普通用户（bot_ 前缀），保存时同步注册进实例并维护资料；
 * 消息回调命中后经 mse-ai 智能体生成回复，管理端 /msg/send_msg 代发（见 ImCallbackService）。
 */
@Service
public class ImBotService {

    private static final String BOT_PREFIX = "bot_";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ImBotMapper botMapper;
    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final MseImProperties properties;

    public ImBotService(ImBotMapper botMapper, ImInstanceResolver instanceResolver,
                        OpenImClient openImClient, MseImProperties properties) {
        this.botMapper = botMapper;
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.properties = properties;
    }

    public List<ImBot> list() {
        return botMapper.selectList(new LambdaQueryWrapper<ImBot>()
                .eq(ImBot::getTenantId, TenantContext.getRequiredTenantId())
                .orderByDesc(ImBot::getId));
    }

    /** 保存并注册进 OpenIM 实例（实例未配置/不可达时失败——机器人依赖实例存在） */
    @Transactional(rollbackFor = Exception.class)
    public ImBot save(ImBotSaveRequest request) {
        Long tenantId = TenantContext.getRequiredTenantId();
        if (request.agentCode() == null || request.agentCode().isBlank()) {
            throw new ServiceException(400, "必须绑定智能体编码");
        }
        ImBot bot;
        boolean creating = request.id() == null;
        if (creating) {
            bot = new ImBot();
            bot.setTenantId(tenantId);
            bot.setBotUserId(request.botUserId() == null || request.botUserId().isBlank()
                    ? BOT_PREFIX + newToken() : normalizeBotUserId(request.botUserId()));
            bot.setStatus(0);
        } else {
            bot = botMapper.selectById(request.id());
            if (bot == null || !tenantId.equals(bot.getTenantId())) {
                throw new ServiceException(404, "机器人不存在");
            }
            if (request.botUserId() != null && !request.botUserId().isBlank()
                    && !bot.getBotUserId().equals(normalizeBotUserId(request.botUserId()))) {
                throw new ServiceException(400, "bot 账号不可变更（如需更换请新建）");
            }
        }
        bot.setNickname(request.nickname() == null || request.nickname().isBlank() ? "机器人" : request.nickname());
        bot.setFaceUrl(request.faceUrl());
        bot.setAgentCode(request.agentCode().trim());
        bot.setTriggerKeywords(request.triggerKeywords());
        bot.setGroupIds(request.groupIds());
        if (request.status() != null) {
            bot.setStatus(request.status());
        }
        // 同步进 OpenIM：未注册则注册，已注册则刷新资料（与用户账号桥同语义）
        var server = instanceResolver.resolve(tenantId);
        var adminToken = openImClient.adminToken(server);
        if (!openImClient.userRegisterIfAbsent(server, adminToken, bot.getBotUserId(), bot.getNickname(), bot.getFaceUrl())) {
            openImClient.updateUserProfile(server, adminToken, bot.getBotUserId(), bot.getNickname(), bot.getFaceUrl());
        }
        if (creating) {
            botMapper.insert(bot);
        } else {
            botMapper.updateById(bot);
        }
        return bot;
    }

    public void delete(Long id) {
        Long tenantId = TenantContext.getRequiredTenantId();
        ImBot bot = botMapper.selectById(id);
        if (bot == null || !tenantId.equals(bot.getTenantId())) {
            throw new ServiceException(404, "机器人不存在");
        }
        botMapper.deleteById(id);
    }

    /** webhook 链路按 bot 账号定位（调用方已 runAs 租户） */
    public ImBot findEnabledByUserId(String botUserId) {
        return botMapper.selectOne(new LambdaQueryWrapper<ImBot>()
                .eq(ImBot::getBotUserId, botUserId)
                .last("limit 1"));
    }

    /** 引用消息兜底触发匹配（调用方已 runAs 租户）：正文含「@昵称」的启用 bot——
     *  前端「引用+@」组合发送走 114 引用消息，atUserList 被丢弃，仅正文保留 @昵称 文本 */
    public List<ImBot> findEnabledByNicknameMention(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ImBot> hits = new java.util.ArrayList<>();
        for (ImBot bot : botMapper.selectList(new LambdaQueryWrapper<ImBot>().eq(ImBot::getStatus, 0))) {
            String nickname = bot.getNickname() == null ? "" : bot.getNickname().trim();
            if (nickname.length() >= 2 && text.contains("@" + nickname)) {
                hits.add(bot);
            }
        }
        return hits;
    }

    /** 触发判定：单聊直发始终触发；群聊须 @ 且命中关键词（未配置关键词 = @ 即触发）与群白名单 */
    public boolean shouldTrigger(ImBot bot, boolean group, String groupID, String text) {
        if (bot.getStatus() != null && bot.getStatus() != 0) {
            return false;
        }
        if (group && bot.getGroupIds() != null && !bot.getGroupIds().isBlank()) {
            boolean hit = false;
            for (String id : bot.getGroupIds().split(",")) {
                if (id.trim().equals(groupID)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        if (!group || bot.getTriggerKeywords() == null || bot.getTriggerKeywords().isBlank()) {
            return true;
        }
        for (String keyword : bot.getTriggerKeywords().split(",")) {
            if (!keyword.isBlank() && text.contains(keyword.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeBotUserId(String value) {
        String id = value.trim();
        if (!id.startsWith(BOT_PREFIX) || id.length() <= BOT_PREFIX.length()) {
            throw new ServiceException(400, "bot 账号必须以 bot_ 开头且非空");
        }
        return id;
    }

    private static String newToken() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** 保存请求（botUserId 缺省自动生成 bot_ 前缀随机号） */
    public record ImBotSaveRequest(Long id, String botUserId, String nickname, String faceUrl,
                                   String agentCode, String triggerKeywords, String groupIds, Integer status) {
    }
}
