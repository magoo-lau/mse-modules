package vip.mseapp.im.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImOauthService;
import vip.mseapp.im.support.WbSec;

import java.io.IOException;
import java.util.Map;

/**
 * 工作台外部应用免登授权（OAuth2 授权码模式，微信接入第三方同款）——公开端点，无需登录。
 *
 * <p>POST /im/oauth/token：对方服务端用 app_key + app_secret + code 兑换 access_token，
 * 兼容 JSON body / 表单 / 查询参数三种提交方式与 client_id/client_secret 惯例别名；
 * GET /im/oauth/userinfo：凭 Bearer token 识别用户身份。
 * 微服务形态经网关 mse.gateway.ignore-urls 放行（/im/oauth/token、/im/oauth/userinfo）。
 */
@RestController
@RequestMapping("/im/oauth")
public class ImOauthController {

    private final ImOauthService oauthService;

    public ImOauthController(ImOauthService oauthService) {
        this.oauthService = oauthService;
    }

    @PostMapping("/token")
    public Result<ImOauthService.TokenVO> token(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Map<String, String> body = readJsonBody(request);
        try {
            ImOauthService.TokenVO vo = oauthService.token(
                    param(body, request, "app_key", "appKey", "client_id", "clientId"),
                    param(body, request, "app_secret", "appSecret", "client_secret", "clientSecret"),
                    param(body, request, "code"));
            return Result.ok(vo);
        } catch (ServiceException e) {
            // 机器凭证语义：错误码映射为真实 HTTP 状态（与开放平台 token 端点同口径）
            response.setStatus(e.getCode());
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @GetMapping("/userinfo")
    public Result<ImOauthService.UserVO> userinfo(HttpServletRequest request) {
        return Result.ok(oauthService.userinfo(request.getHeader("Authorization")));
    }

    /** 依次从 JSON body 与查询/表单参数取值（首个非空命中） */
    private static String param(Map<String, String> body, HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = body.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        for (String name : names) {
            String value = request.getParameter(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** 解析 JSON 请求体（非 JSON 提交返回空 Map；表单/查询参数走 request.getParameter） */
    private static Map<String, String> readJsonBody(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().contains("application/json")) {
            return Map.of();
        }
        JsonNode node;
        try {
            node = WbSec.JSON.readTree(request.getInputStream());
        } catch (Exception e) {
            throw new ServiceException(400, "请求体不是合法 JSON");
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            JsonNode value = field.getValue();
            if (value != null && value.isValueNode()) {
                values.put(field.getKey(), value.asText());
            }
        }
        return values;
    }
}
