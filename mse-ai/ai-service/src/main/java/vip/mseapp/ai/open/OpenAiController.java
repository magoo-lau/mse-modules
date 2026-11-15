package vip.mseapp.ai.open;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.mseapp.ai.entity.AiAgent;
import vip.mseapp.ai.entity.AiApiKey;
import vip.mseapp.ai.service.AiAgentService;
import vip.mseapp.ai.service.AiChatService;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.Result;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 开放 API（/openapi/ai/**，网关白名单 + 过滤器鉴权）：第三方系统直调 AI 能力。
 *
 * <p>调用以密钥绑定租户身份执行；受智能体白名单（ai_api_key.agent_codes）约束；
 * 用量按 openapi 通道归集到该租户。
 */
@Validated
@RestController
@RequestMapping("/openapi/ai")
public class OpenAiController {

    private final AiChatService chatService;
    private final AiAgentService agentService;

    public OpenAiController(AiChatService chatService, AiAgentService agentService) {
        this.chatService = chatService;
        this.agentService = agentService;
    }

    /** 当前密钥可用的智能体列表（白名单 ∩ 租户已发布） */
    @GetMapping("/agents")
    public Result<List<AiAgent>> agents(HttpServletRequest request) {
        AiApiKey key = currentKey(request);
        List<AiAgent> visible = agentService.listVisible(key.getTenantId());
        if (key.getAgentCodes() == null || key.getAgentCodes().isBlank()) {
            return Result.ok(visible);
        }
        Set<String> whitelist = Arrays.stream(key.getAgentCodes().split(","))
                .map(String::trim).collect(Collectors.toSet());
        return Result.ok(visible.stream().filter(a -> whitelist.contains(a.getAgentCode())).toList());
    }

    /** 同步对话（开放通道无登录用户，走无状态单轮：不建会话行，伪会话号返回） */
    @PostMapping("/chat")
    public Result<AiChatResultView> chat(HttpServletRequest request,
                                         @RequestBody @Validated OpenChatRequest body) {
        AiApiKey key = currentKey(request);
        checkAgentAllowed(key, body.agentCode());
        AiChatService.ResolvedChat ctx = chatService.prepareStatelessOpen(body.agentCode(), key.getTenantId());
        String content = chatService.chat(ctx, body.message(), null);
        return Result.ok(new AiChatResultView(content, ctx.sessionId(), ctx.model()));
    }

    /** 流式对话（SSE，事件同网关通道；开放通道无状态单轮） */
    @PostMapping("/chat/stream")
    public SseEmitter stream(HttpServletRequest request, @RequestBody @Validated OpenChatRequest body) {
        AiApiKey key = currentKey(request);
        checkAgentAllowed(key, body.agentCode());
        AiChatService.ResolvedChat ctx = chatService.prepareStatelessOpen(body.agentCode(), key.getTenantId());
        return chatService.stream(ctx, body.message(), null);
    }

    private AiApiKey currentKey(HttpServletRequest request) {
        Object attribute = request.getAttribute(OpenApiFilterConfig.ATTR_KEY);
        if (!(attribute instanceof AiApiKey key)) {
            throw new ServiceException(401, "未通过开放 API 鉴权");
        }
        return key;
    }

    private void checkAgentAllowed(AiApiKey key, String agentCode) {
        if (key.getAgentCodes() != null && !key.getAgentCodes().isBlank()
                && Arrays.stream(key.getAgentCodes().split(",")).map(String::trim)
                        .noneMatch(agentCode::equals)) {
            throw new ServiceException(403, "该应用未授权使用智能体: " + agentCode);
        }
    }

    public record OpenChatRequest(
            @NotBlank(message = "agentCode 不能为空") String agentCode,
            @NotBlank(message = "message 不能为空") String message,
            String conversationId) {
    }

    public record AiChatResultView(String content, String conversationId, String model) {
    }
}
