package vip.mseapp.open.security;

import java.io.Serializable;

/**
 * 应用调用会话（access_token 在 Redis 中的负载）。
 *
 * <p>tenantId 仅为签发时快照（审计参考）；运行期租户上下文始终以 open_app 实时绑定的
 * tenantId 为准，避免应用改绑租户后旧 token 残留旧租户语义。
 */
public record OpenAppSession(Long appId, String appKey, Long tenantId) implements Serializable {
}
