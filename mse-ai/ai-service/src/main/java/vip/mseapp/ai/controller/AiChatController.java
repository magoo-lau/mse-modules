package vip.mseapp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mseapp.ai.entity.AiChatMessage;
import vip.mseapp.ai.entity.AiChatSession;
import vip.mseapp.ai.mapper.AiChatMessageMapper;
import vip.mseapp.ai.service.AiChatService;
import vip.mseapp.ai.service.DbChatMemory;
import vip.mseapp.ai.support.AiJson;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.security.stp.StpTenantUtil;

import java.util.List;
import java.util.Map;

/**
 * AI 对话（租户端）：SSE 流式 / 同步 / 会话历史。
 *
 * <p>登录态由网关 + 共享 Redis 保证；本控制器仅做细粒度权限校验。
 */
@Validated
@RestController
@RequestMapping("/ai/chat")
public class AiChatController {

    private final AiChatService chatService;
    private final DbChatMemory chatMemory;
    private final AiChatMessageMapper messageMapper;

    public AiChatController(AiChatService chatService, DbChatMemory chatMemory,
                            AiChatMessageMapper messageMapper) {
        this.chatService = chatService;
        this.chatMemory = chatMemory;
        this.messageMapper = messageMapper;
    }

    /**
     * SSE 流式对话。
     *
     * <p>事件流：message（{"delta":"..."}）→ tool（工具轨迹与模型轮次边界，{@code ai:chat:trace} 权限者）
     * → usage / done / error。鉴权在返回 emitter 前完成（@SaCheckPermission 生效于进入方法前）。
     */
    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/stream")
    public SseEmitter stream(@RequestBody @Validated ChatRequest request) {
        AiChatService.ResolvedChat ctx = chatService.prepare(request.agentCode(), request.conversationId(),
                request.message(), TenantContext.getRequiredTenantId(), UserContext.getUserId(), "gateway");
        // 持轨迹权限者开启详细轨迹：模型轮次边界 + 每轮实际输入消息快照（SSE turn 事件 + ext.turns 回放）
        chatService.enableVerboseTrace(ctx);
        return chatService.stream(ctx, request.message(), request.variables(), hasTracePermission());
    }

    /** 同步对话（非流式场景） */
    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<String> chat(@RequestBody @Validated ChatRequest request) {
        AiChatService.ResolvedChat ctx = chatService.prepare(request.agentCode(), request.conversationId(),
                request.message(), TenantContext.getRequiredTenantId(), UserContext.getUserId(), "gateway");
        return Result.ok(chatService.chat(ctx, request.message(), request.variables()));
    }

    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/session/page")
    public Result<PageResult<AiChatSession>> sessions(@RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "50") long pageSize,
                                                      @RequestParam(required = false) String agentCode) {
        Page<AiChatSession> page = chatService.pageSessions(pageNum, pageSize, UserContext.getUserId(), agentCode);
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/session/{id}/messages")
    public Result<List<AiChatMessage>> messages(@PathVariable Long id) {
        // 校验归属（跨用户不可读）；消息随世界路由（与写入链同世界：独立库租户专属库/共享租户绑定库）
        AiChatSession session = TenantContext.runAs(TenantContext.getRequiredTenantId(), () ->
                chatMemory.requireSession(String.valueOf(id)));
        if (!UserContext.getUserId().equals(session.getUserId())) {
            return Result.fail(403, "无权查看该会话");
        }
        List<AiChatMessage> messages = TenantContext.runAs(session.getTenantId(), () -> messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getSessionId, String.valueOf(id))
                        .orderByAsc(AiChatMessage::getId)));
        // 工具调用轨迹（入参/返回明细）与轮次输入快照（含记忆历史/检索内容）按权限下发：
        // 无 ai:chat:trace 者剔除 ext.toolCalls 与 ext.turns，datasets/sources（数据卡片/引用面板）不受影响
        if (!hasTracePermission()) {
            messages.forEach(this::stripTrace);
        }
        return Result.ok(messages);
    }

    /** 当前用户是否可见工具调用轨迹（无会话上下文等异常场景降级为不可见） */
    private static boolean hasTracePermission() {
        try {
            return StpTenantUtil.stpLogic.hasPermission("ai:chat:trace");
        } catch (Exception e) {
            return false;
        }
    }

    /** 剔除消息 ext 中的工具轨迹与轮次快照（其余扩展信息原样保留；非法 ext 原样返回） */
    private void stripTrace(AiChatMessage message) {
        String ext = message.getExt();
        if (ext == null || ext.isBlank()
                || (!ext.contains("toolCalls") && !ext.contains("turns"))) {
            return;
        }
        try {
            Map<String, Object> extMap = AiJson.toMap(ext);
            boolean removed = extMap.remove("toolCalls") != null | extMap.remove("turns") != null;
            if (removed) {
                message.setExt(AiJson.toJson(extMap));
            }
        } catch (Exception ignored) {
            // ext 非法 JSON：原样返回
        }
    }

    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/session/{id}/title")
    public Result<Void> rename(@PathVariable Long id, @RequestBody @Validated RenameRequest request) {
        chatService.renameSession(id, UserContext.getUserId(), request.title());
        return Result.ok();
    }

    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/session/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        chatService.deleteSession(id, UserContext.getUserId(), chatMemory);
        return Result.ok();
    }

    /**
     * 停止运行中的编排对话（本地取消 + Redis 跨实例广播；简单模式/未在运行返回 false，幂等）。
     */
    @SaCheckPermission(value = "ai:chat:use", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/stream/stop")
    public Result<Boolean> stop(@RequestBody @Validated StopRequest request) {
        return Result.ok(chatService.stopFlowRun(request.sessionId(), UserContext.getUserId()));
    }

    public record ChatRequest(
            @NotBlank(message = "agentCode 不能为空") String agentCode,
            @NotBlank(message = "消息不能为空") String message,
            String conversationId,
            Map<String, String> variables) {
    }

    public record RenameRequest(@NotBlank(message = "标题不能为空") String title) {
    }

    public record StopRequest(@NotBlank(message = "sessionId 不能为空") String sessionId) {
    }
}
