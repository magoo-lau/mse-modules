package vip.mseapp.im.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImSsoCodeService;

/**
 * 工作台 SSO 一次性 code 端点。
 *
 * <p>/im/sso/code 需登录态（聊天客户端调用）；/im/sso/exchange 在网关白名单放行
 * （code 本身即一次性凭证，浏览器 Origin 与 code 绑定站点强校验）。
 */
@Validated
@RestController
@RequestMapping("/im/sso")
public class ImSsoController {

    private final ImSsoCodeService ssoCodeService;

    public ImSsoController(ImSsoCodeService ssoCodeService) {
        this.ssoCodeService = ssoCodeService;
    }

    /** 签发工作台跳转 URL（登录态）：targetUrl 为 mse 侧完整跳转地址 */
    @PostMapping("/code")
    public Result<ImSsoCodeService.SsoCodeVO> issue(@Valid @RequestBody CodeRequest request) {
        return Result.ok(ssoCodeService.issue(request.targetUrl()));
    }

    /** code 换标准租户会话（免登录白名单）：浏览器必带 Origin，服务端与 code 绑定站点比对 */
    @PostMapping("/exchange")
    public Result<ImSsoCodeService.LoginVO> exchange(@Valid @RequestBody ExchangeRequest request,
                                                     HttpServletRequest httpRequest) {
        return Result.ok(ssoCodeService.exchange(request.code(), httpRequest.getHeader("Origin")));
    }

    record CodeRequest(@NotBlank @Size(max = 500) String targetUrl) {
    }

    record ExchangeRequest(@NotBlank @Size(max = 128) String code) {
    }
}
