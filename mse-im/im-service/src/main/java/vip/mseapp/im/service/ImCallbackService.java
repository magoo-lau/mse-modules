package vip.mseapp.im.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import vip.mseapp.ai.api.RemoteAiChatService;
import vip.mseapp.ai.api.dto.AiChatRequest;
import vip.mseapp.ai.api.dto.AiChatResult;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImBot;
import vip.mseapp.im.entity.ImInstanceConfig;
import vip.mseapp.im.support.ImInstanceResolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import vip.mseapp.core.concurrent.MseExecutors;

/**
 * OpenIM webhook 消费：单入口 /im/callback/{tenantId}/{token}/{callbackCommand}
 * （tenantId 显式路由到租户库、token 校验凭据，弥补开源回调无签名），事件分发走异步线程池
 * （before 类回调才有 5s 阻断语义，本服务只订阅 after 类——响应不影响 IM 行为，
 * 但仍需及时应答避免回调堆积）。
 *
 * <p>已消费事件：afterUserOnline（停用账号踢线，{@link ImLifecycleService}）、
 * afterSendSingleMsg / afterSendGroupMsg（机器人：命中触发条件 → mse-ai 智能体 →
 * 管理端 /msg/send_msg 代发回复，回复携带本会话最近聊天上下文，见 {@link ImBotContextService}）。
 * 幂等：operationID Redis SETNX 去重（120s 窗口）。
 */
@Service
public class ImCallbackService {

    private static final Logger log = LoggerFactory.getLogger(ImCallbackService.class);
    private static final String DEDUP_KEY_PREFIX = "im:cb:dedup:";
    private static final Duration DEDUP_TTL = Duration.ofSeconds(120);

    /** 回调处理线程池（daemon：不阻碍停机；任务内全程自捕获异常）。经 MseExecutors 工厂：
     *  有界固定池替代原 cached（无上限线程是隐患），饱和时 CallerRuns 由回调线程背压；
     *  wrap 池内恢复提交线程租户（提交点显式 runAs 保留作双保险） */
    private final ExecutorService executor = MseExecutors.fixedPool("im-callback", 4, 200);

    /** 机器人 AI 回复（LLM 生成耗时 1~3 分钟，超时须大于单次模型调用上限，注解默认 5s 必掐） */
    @RpcReference(dubbo = @RpcReference.Dubbo(timeout = 180_000))
    private RemoteAiChatService remoteAiChatService;

    private final ImInstanceService instanceService;
    private final ImLifecycleService lifecycleService;
    private final ImBotService botService;
    private final ImBotContextService botContextService;
    private final ImMsgAuditService msgAuditService;
    private final ImPresenceService presenceService;
    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final StringRedisTemplate redis;

    public ImCallbackService(ImInstanceService instanceService, ImLifecycleService lifecycleService,
                             ImBotService botService, ImBotContextService botContextService,
                             ImMsgAuditService msgAuditService,
                             ImPresenceService presenceService, ImInstanceResolver instanceResolver,
                             OpenImClient openImClient, StringRedisTemplate redis) {
        this.instanceService = instanceService;
        this.lifecycleService = lifecycleService;
        this.botService = botService;
        this.botContextService = botContextService;
        this.msgAuditService = msgAuditService;
        this.presenceService = presenceService;
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.redis = redis;
    }

    /**
     * 回调入口：tenantId 显式路由到租户库、token 校验凭据（未知组合一律 404，防扫描）→
     * operationID 去重 → before 类同步应答 actionCode（放行/拒绝语义）/ after 类异步分发并立即应答空对象。
     */
    public Map<String, Object> handle(Long tenantId, String token, String callbackCommand, JsonNode body) {
        ImInstanceConfig row = instanceService.findByTenantAndToken(tenantId, token);
        if (row == null || row.getStatus() != null && row.getStatus() != 0) {
            throw new ServiceException(404, "callback not found");
        }
        String operationId = body.path("operationID").asText("");
        if (operationId.isBlank()) {
            operationId = tenantId + ":" + token + ":" + System.nanoTime();
        }
        Boolean first = redis.opsForValue().setIfAbsent(DEDUP_KEY_PREFIX + operationId, "1", DEDUP_TTL);
        if (first != null && !first) {
            return Map.of(); // 重复投递：直接应答成功
        }
        String command = normalizeCommand(callbackCommand);
        // before 类：同步应答 actionCode（放行/拒绝语义），必须即时返回
        if ("beforeUserRegister".equals(command)) {
            return beforeUserRegister(body);
        }
        executor.submit(() -> dispatch(tenantId, command, body));
        return Map.of();
    }

    /**
     * OpenIM v3.8 实际投递的 callbackCommand 是 {@code callbackXxxCommand} 形态
     * （如 callbackAfterSendGroupMsgCommand），而配置/联调口径是裸事件名
     * （afterSendGroupMsg）——此处统一剥前缀去后缀转小写驼峰，两种形态归一后才分发；
     * 不归一则全部事件落入 default 被静默丢弃（机器人/审计/在线状态全失效）。
     */
    private static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String c = command.trim();
        if (c.startsWith("callback")) {
            c = c.substring("callback".length());
        }
        if (c.endsWith("Command")) {
            c = c.substring(0, c.length() - "Command".length());
        }
        if (!c.isEmpty()) {
            c = Character.toLowerCase(c.charAt(0)) + c.substring(1);
        }
        return c;
    }

    /**
     * 直连旁路加固：注册 userID 仅放行「全角数字（sys_user.id 雪花）」与「bot_ 前缀（机器人）」，
     * 其余一律拒绝。边界说明：mse-im 与实例共享 admin secret，持有 secret 即可注册——
     * 本校验是纵深防御而非认证边界（社区版回调无签名、无登录前回调，认证权威在 mse 持有的 secret）。
     */
    private Map<String, Object> beforeUserRegister(JsonNode body) {
        String userID = body.path("userID").asText("");
        boolean allowed = userID.matches("\\d+") || userID.startsWith("bot_");
        if (allowed) {
            return Map.of("actionCode", 0);
        }
        log.info("[mse-im] 拒绝非 mse 口径的注册: userID={}", userID);
        return Map.of("actionCode", 1, "errCode", 1, "errMsg", "userID 必须为业务用户编号或 bot_ 前缀");
    }

    private void dispatch(Long tenantId, String callbackCommand, JsonNode body) {
        try {
            switch (callbackCommand == null ? "" : callbackCommand) {
                case "afterUserOnline" -> {
                    presenceService.online(tenantId, body.path("userID").asText(""), body.path("platformID").asInt(5));
                    lifecycleService.onUserOnline(tenantId, body.path("userID").asText(""), body.path("platformID").asInt(5));
                }
                case "afterUserOffline" -> presenceService.offline(tenantId, body.path("userID").asText(""), body.path("platformID").asInt(5));
                case "afterUserKickOff" -> presenceService.offline(tenantId, body.path("userID").asText(""), body.path("platformID").asInt(5));
                case "afterSendSingleMsg" -> {
                    msgAuditService.record(tenantId, body);
                    onSingleMessage(tenantId, body);
                }
                case "afterSendGroupMsg" -> {
                    msgAuditService.record(tenantId, body);
                    onGroupMessage(tenantId, body);
                }
                default -> log.debug("[mse-im] 未消费的回调事件: {}", callbackCommand);
            }
        } catch (Exception e) {
            log.warn("[mse-im] 回调处理失败: command={} err={}", callbackCommand, e.getMessage());
        }
    }

    /** 单聊：recvID 是启用中的 bot 即触发（直发始终视为意图） */
    private void onSingleMessage(Long tenantId, JsonNode body) {
        if (isFromBot(body)) {
            return;
        }
        String recvID = body.path("recvID").asText("");
        ImBot bot = TenantContext.runAs(tenantId, () -> botService.findEnabledByUserId(recvID));
        if (bot == null) {
            return;
        }
        String question = ImMsgAuditService.extractText(body);
        if (question.isBlank()) {
            return;
        }
        if (!botService.shouldTrigger(bot, false, null, question)) {
            return;
        }
        replyAsync(tenantId, bot, body, question);
    }

    /**
     * 群聊触发：① atUserList 命中启用中的 bot（@ 选择器路径，106 AtText）；
     * ② 引用消息兜底——前端「引用+@」组合发送走 114（atUserList 被丢弃，正文保留 @昵称 文本），
     * 对启用 bot 做正文「@昵称」匹配。命中后经群白名单/关键词判定 → 回复并 @ 提问者。
     */
    private void onGroupMessage(Long tenantId, JsonNode body) {
        if (isFromBot(body)) {
            return; // 防环：bot 代发消息不再触发任何 bot（防 bot 间互 @ 死循环）
        }
        List<String> atUsers = new ArrayList<>();
        JsonNode atNode = body.path("atUserList");
        if (atNode.isArray()) {
            atNode.forEach(n -> atUsers.add(n.asText("")));
        }
        String groupID = body.path("groupID").asText("");
        String question = null;
        ImBot hit = null;
        for (String atUser : atUsers) {
            ImBot bot = TenantContext.runAs(tenantId, () -> botService.findEnabledByUserId(atUser));
            if (bot == null) {
                continue;
            }
            if (question == null) {
                question = ImMsgAuditService.extractText(body);
            }
            if (question.isBlank() || !botService.shouldTrigger(bot, true, groupID, question)) {
                continue;
            }
            hit = bot;
            break;
        }
        if (hit == null && atUsers.isEmpty() && body.path("contentType").asInt(0) == 114) {
            String text = ImMsgAuditService.extractText(body);
            for (ImBot bot : TenantContext.runAs(tenantId, () -> botService.findEnabledByNicknameMention(text))) {
                if (text.isBlank() || !botService.shouldTrigger(bot, true, groupID, text)) {
                    continue;
                }
                hit = bot;
                question = text;
                break;
            }
        }
        if (hit != null) {
            replyAsync(tenantId, hit, body, question);
        }
    }

    private static boolean isFromBot(JsonNode body) {
        String sendID = body.path("sendID").asText("");
        return sendID.startsWith("bot_");
    }

    /** AI 对话 + 代发回复：message 由 {@link ImBotContextService} 组装（本会话最近聊天记录 + 当前提问，
     *  无 AI 侧会话状态，多轮语义靠消息存档承载）；AI 失败降级为友好提示（不让提问者面对静默） */
    private void replyAsync(Long tenantId, ImBot bot, JsonNode body, String question) {
        executor.submit(() -> TenantContext.runAs(tenantId, () -> {
            try {
                boolean group = body.path("sessionType").asInt(1) == 3;
                String message = botContextService.buildPrompt(tenantId, bot, group,
                        body.path("groupID").asText(""), body.path("sendID").asText(""),
                        body.path("sendNickname").asText(""), body.path("clientMsgID").asText(""), question);
                AiChatRequest request = new AiChatRequest();
                request.setAgentCode(bot.getAgentCode());
                request.setMessage(message);
                AiChatResult result = remoteAiChatService.chat(request);
                String content = result.getContent() == null || result.getContent().isBlank()
                        ? "（智能体未返回内容）" : result.getContent();
                send(bot, body.path("sendID").asText(""), group ? body.path("groupID").asText("") : null, content);
            } catch (Exception e) {
                log.warn("[mse-im] 机器人回复失败（降级提示）: bot={} err={}", bot.getBotUserId(), e.getMessage());
                try {
                    send(bot, body.path("sendID").asText(""),
                            body.path("sessionType").asInt(1) == 3 ? body.path("groupID").asText("") : null,
                            "机器人暂时不可用，请稍后再试");
                } catch (Exception ignored) {
                    // 实例不可达时连降级提示也发不出，仅记日志
                }
            }
        }));
    }

    /** 管理端代发：单聊 101 文本；群聊 106 AtText @ 提问者；昵称/头像随消息下发（否则客户端只能回退展示 bot 账号） */
    private void send(ImBot bot, String senderID, String groupID, String text) {
        Long tenantId = TenantContext.getRequiredTenantId();
        MseImProperties.Server server = instanceResolver.resolve(tenantId);
        String adminToken = openImClient.adminToken(server);
        boolean group = groupID != null && !groupID.isBlank();
        if (group) {
            openImClient.sendMsg(server, adminToken, bot.getBotUserId(), null, groupID, 3, 106,
                    Map.of("text", text, "atUserList", List.of(senderID), "isAtSelf", false),
                    bot.getNickname(), bot.getFaceUrl());
        } else {
            openImClient.sendMsg(server, adminToken, bot.getBotUserId(), senderID, null, 1, 101,
                    Map.of("content", text),
                    bot.getNickname(), bot.getFaceUrl());
        }
    }

    /** 提取消息文本口径统一走 {@link ImMsgAuditService#extractText}（按 contentType 摘要，引用含被引用内容） */
}
