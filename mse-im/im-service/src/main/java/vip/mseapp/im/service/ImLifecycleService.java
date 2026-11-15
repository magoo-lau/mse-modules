package vip.mseapp.im.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.support.ImInstanceResolver;

/**
 * IM 侧用户生命周期联动：OpenIM 上线回调时复核 mse 侧账号状态，已停用/删除立即
 * 强制下线（force_logout，PoC 定型接口）——堵住「改密/禁用前已签发的长有效期 IM token」窗口。
 */
@Service
public class ImLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ImLifecycleService.class);

    /** OpenIM platformID 全集：停用账号逐端型清退（未在线端型的 forceLogout 报错忽略） */
    private static final int[] ALL_PLATFORM_IDS = {1, 2, 3, 4, 5, 6, 7};

    @RpcReference
    private RemoteUserService remoteUserService;

    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;

    public ImLifecycleService(ImInstanceResolver instanceResolver, OpenImClient openImClient) {
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
    }

    /**
     * 上线事件处理：启用用户放行；停用/删除用户逐端型强制下线。
     * 调用方在回调异步线程执行（5s 窗口内先应答），异常只记日志不影响回调应答。
     */
    public void onUserOnline(Long tenantId, String userID, int platformId) {
        Long userId;
        try {
            userId = Long.valueOf(userID);
        } catch (NumberFormatException e) {
            return; // bot_ 等非 sys_user.id 账号不经此链路
        }
        TenantContext.runAs(tenantId, () -> {
            try {
                UserDTO user = remoteUserService.getUserById(userId);
                boolean enabled = user != null && user.getStatus() != null && user.getStatus() == 0;
                if (enabled) {
                    return;
                }
                MseImProperties.Server server = instanceResolver.resolve(tenantId);
                String adminToken = openImClient.adminToken(server);
                for (int pid : ALL_PLATFORM_IDS) {
                    try {
                        openImClient.forceLogout(server, adminToken, userID, pid);
                    } catch (Exception ignored) {
                        // 未在线端型 forceLogout 报错忽略；本次上线端型由 platformId 兜底再踢
                    }
                }
                openImClient.forceLogout(server, adminToken, userID, platformId);
                log.warn("[mse-im] 已停用账号上线，已强制下线 IM: tenantId={} userID={}", tenantId, userID);
            } catch (Exception e) {
                log.warn("[mse-im] 上线状态复核失败（不影响回调应答）: tenantId={} userID={} err={}",
                        tenantId, userID, e.getMessage());
            }
        });
    }
}
