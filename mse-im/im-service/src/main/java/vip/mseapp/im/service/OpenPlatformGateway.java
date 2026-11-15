package vip.mseapp.im.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.open.api.OpenAppBrief;
import vip.mseapp.open.api.OpenAppCheck;
import vip.mseapp.open.api.RemoteOpenAppService;

import java.util.List;

/**
 * 开放平台应用契约网关（mse-open 可用时装配，见 OpenAppAvailableCondition）。
 *
 * <p>工作台免登授权的凭证归口：app_key/app_secret/域名白名单全部在开放平台「应用管理」维护，
 * 本网关只做只读消费——保存时的存在性/归属校验、jump 签发授权码前的回调域名校验、
 * /im/oauth/token 兑换时的凭证校验。im 侧不落任何密钥。
 */
public class OpenPlatformGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenPlatformGateway.class);

    @RpcReference
    private RemoteOpenAppService remoteOpenAppService;

    public OpenPlatformGateway() {
        log.info("[mse-im] 开放平台应用契约网关已装配（工作台免登授权可用）");
    }

    /** 本租户应用清单（免登入口下拉候选，含停用行由前端标注禁选） */
    public List<OpenAppBrief> listApps(Long tenantId) {
        return remoteOpenAppService.listApps(tenantId);
    }

    /** 按编号取应用（保存关联校验用）；不存在返回 null */
    public OpenAppBrief getApp(Long appId) {
        return remoteOpenAppService.getApp(appId);
    }

    /** 域名白名单校验（jump 签发授权码前的回调域安全检查） */
    public boolean domainAllowed(String appKey, String host) {
        return remoteOpenAppService.domainAllowed(appKey, host);
    }

    /** 凭证校验（兑换 access_token 前）：常量时间比对 + 状态/过期即时生效 */
    public OpenAppCheck checkCredential(String appKey, String appSecret) {
        return remoteOpenAppService.checkCredential(appKey, appSecret);
    }
}
