package vip.mseapp.im.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vip.mseapp.im.service.ImCallbackService;

import java.util.Map;

/**
 * OpenIM webhook 接收入口（免登录：tenantId 显式路由 + token 校验，未登记组合一律 404）。
 *
 * <p>OpenIM 以 {@code POST {webhooks.yml url}/{callbackCommand}} 投递，故路径为
 * {@code /im/callback/{tenantId}/{token}/{callbackCommand}}——租户段直接决定查哪个库
 * （独立库租户的台账行在自有库，靠 token 反查租户的间接映射已退役）；需在实例
 * webhooks.yml 配置 url 指向 mse-im 并订阅所消费的 after 类事件。网关白名单已放行
 * /im/callback/**。
 */
@RestController
public class ImCallbackController {

    private final ImCallbackService callbackService;

    public ImCallbackController(ImCallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping("/im/callback/{tenantId}/{token}/{command}")
    public Map<String, Object> onCallback(@PathVariable Long tenantId,
                                          @PathVariable String token,
                                          @PathVariable String command,
                                          @RequestBody(required = false) JsonNode body) {
        return callbackService.handle(tenantId, token, command, body);
    }
}
