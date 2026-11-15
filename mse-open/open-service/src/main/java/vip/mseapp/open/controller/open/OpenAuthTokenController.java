package vip.mseapp.open.controller.open;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.security.OpenAppSession;
import vip.mseapp.open.security.OpenTokenService;

/**
 * 应用 access_token 签发（公开端点，无需登录）。
 *
 * <p>支持 JSON body 与表单/查询参数两种提交方式，便于各类接入语言对接：
 * POST /open/oauth/token  {"appKey": "...", "appSecret": "..."}
 *
 * <p>吊销：DELETE 语义本期从简——凭证有效期短（默认 2h），泄露时在管理端停用应用即时失效。
 */
@RestController
@RequestMapping("/open/oauth")
public class OpenAuthTokenController {

    private final OpenTokenService tokenService;

    public OpenAuthTokenController(OpenTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /** 签发结果 */
    public record TokenIssued(String accessToken, long expiresIn, String tokenType) {
    }

    record TokenRequest(String appKey, String appSecret) {
    }

    @PostMapping("/token")
    public Result<TokenIssued> token(@RequestBody(required = false) TokenRequest body,
                                     @RequestParam(required = false) String appKey,
                                     @RequestParam(required = false) String appSecret,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        String key = body != null && body.appKey() != null ? body.appKey() : appKey;
        String secret = body != null && body.appSecret() != null ? body.appSecret() : appSecret;
        try {
            OpenTokenService.TokenIssue issue = tokenService.issue(key, secret, request);
            return Result.ok(new TokenIssued(issue.accessToken(), issue.expiresIn(), "Bearer"));
        } catch (ServiceException e) {
            // 机器凭证语义：错误码映射为真实 HTTP 状态（与开放 API 拦截器的 401 行为一致）
            response.setStatus(e.getCode());
            return Result.fail(e.getCode(), e.getMessage());
        }
    }
}
