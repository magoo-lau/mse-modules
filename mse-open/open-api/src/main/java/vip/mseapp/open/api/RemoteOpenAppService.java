package vip.mseapp.open.api;

import java.util.List;

/**
 * 开放平台接入应用契约（mse-open 实现，业务积木消费）。
 *
 * <p>凭证体系的归口：app_key/app_secret 的生成、重置与启停全部在开放平台「应用管理」维护，
 * 业务积木（如 mse-im 工作台免登授权）只做「选择已有应用 + 运行期校验」，不落任何密钥。
 * 查询与校验均面向 open_app 全局表（业务主库驻留），与调用方租户上下文的独立库路由无关。
 */
public interface RemoteOpenAppService {

    /**
     * 租户名下应用清单（下拉候选；含停用行，调用方自行标注禁选）。
     *
     * @param tenantId 应用绑定租户
     */
    List<OpenAppBrief> listApps(Long tenantId);

    /**
     * 按编号取应用（保存关联时的存在性/归属/状态校验用）；不存在返回 null。
     */
    OpenAppBrief getApp(Long appId);

    /**
     * 域名白名单校验（code 授权模式签发授权码前的回调域安全检查，同微信公众号授权回调域语义）。
     *
     * @param appKey 应用标识
     * @param host   请求来源/入口域名（裸域名，不含协议/端口/路径）
     * @return true = 放行（应用未配置白名单 = 不限制）；应用不存在或未命中返回 false
     */
    boolean domainAllowed(String appKey, String host);

    /**
     * 应用凭证校验：app_key + app_secret 常量时间比对 + 状态/过期检查。
     * 应用在开放平台被停用/过期/删除，校验即时失败。
     */
    OpenAppCheck checkCredential(String appKey, String appSecret);
}
