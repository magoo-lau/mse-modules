package vip.mseapp.open.rpc;

import vip.mseapp.core.annotation.RpcService;
import vip.mseapp.open.api.OpenAppBrief;
import vip.mseapp.open.api.OpenAppCheck;
import vip.mseapp.open.api.RemoteOpenAppService;
import vip.mseapp.open.security.OpenTokenService;
import vip.mseapp.open.service.OpenAppService;

import java.util.List;

/**
 * 接入应用契约实现（业务积木消费：mse-im 工作台免登授权等「选择已有应用 + 运行期校验」场景）。
 *
 * <p>凭证的生成/重置/启停归口管理端（/openapp/app/**），本契约只读 + 校验，
 * 不提供任何凭证变更能力。
 */
@RpcService(RemoteOpenAppService.class)
public class RemoteOpenAppServiceImpl implements RemoteOpenAppService {

    private final OpenAppService appService;
    private final OpenTokenService tokenService;

    public RemoteOpenAppServiceImpl(OpenAppService appService, OpenTokenService tokenService) {
        this.appService = appService;
        this.tokenService = tokenService;
    }

    @Override
    public List<OpenAppBrief> listApps(Long tenantId) {
        return appService.listBriefs(tenantId);
    }

    @Override
    public OpenAppBrief getApp(Long appId) {
        return appService.brief(appId);
    }

    @Override
    public boolean domainAllowed(String appKey, String host) {
        return tokenService.domainAllowed(appKey, host);
    }

    @Override
    public OpenAppCheck checkCredential(String appKey, String appSecret) {
        return tokenService.checkCredential(appKey, appSecret);
    }
}
