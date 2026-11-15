package vip.mseapp.im.service;

import org.springframework.stereotype.Service;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.support.ImInstanceResolver;
import vip.mseapp.security.stp.StpTenantUtil;

import java.util.Set;

/**
 * IM 会话签发：登录态用户 → OpenIM userID + IM token + 客户端连接地址。
 *
 * <p>userID 直接复用 sys_user.id（免映射，官方迁移指南同款建议）；昵称/头像经
 * {@link RemoteUserService} 契约拉取（不直连基座表），并顺带复核用户启用状态。
 */
@Service
public class ImSessionService {

    /** OpenIM platformID：1 iOS / 2 Android / 3 Windows / 4 OSX / 5 Web / 6 小程序 / 7 Linux */
    private static final Set<Integer> PLATFORM_IDS = Set.of(1, 2, 3, 4, 5, 6, 7);
    private static final int DEFAULT_PLATFORM_ID = 5;

    @RpcReference
    private RemoteUserService remoteUserService;

    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final ImDeptGroupService deptGroupService;
    private final ImRtcService rtcService;
    private final ImProfileService profileService;

    public ImSessionService(ImInstanceResolver instanceResolver, OpenImClient openImClient,
                            ImDeptGroupService deptGroupService, ImRtcService rtcService,
                            ImProfileService profileService) {
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.deptGroupService = deptGroupService;
        this.rtcService = rtcService;
        this.profileService = profileService;
    }

    public SessionVO session(Integer platformId) {
        Long tenantId = StpTenantUtil.getTenantIdFromLoginId();
        Long userId = StpTenantUtil.getUserIdFromLoginId();
        MseImProperties.Server server = instanceResolver.resolve(tenantId);
        int pid = platformId == null ? DEFAULT_PLATFORM_ID : platformId;
        if (!PLATFORM_IDS.contains(pid)) {
            throw new ServiceException(400, "非法 platformID：" + pid + "（合法 1-7）");
        }
        UserDTO user = remoteUserService.getUserById(userId);
        // SysUser.status：0 启用 1 停用（仓库惯例）
        if (user == null || user.getStatus() == null || user.getStatus() != 0) {
            throw new ServiceException(403, "用户不存在或已停用");
        }
        String userID = String.valueOf(userId);
        String nickname = user.getNickname() == null || user.getNickname().isBlank() ? user.getUsername() : user.getNickname();
        // 生效头像三态：IM 自设 > 基座 avatar（原样透传，站内相对路径由客户端 assetUrl 解析）
        String faceURL = profileService.resolveFaceUrl(tenantId, userId, user.getAvatar());
        ensureUser(server, userID, nickname, faceURL);
        // 部门群保障（异步不阻断签发）：防抖全量对账 + 保障本人已入所在部门群
        deptGroupService.ensureForUserAsync(tenantId, userId, user.getDeptId());
        OpenImClient.UserToken token = openImClient.userToken(server, openImClient.adminToken(server), userID, pid);
        return new SessionVO(userID, token.token(), instanceResolver.apiAddr(server), instanceResolver.wsAddr(server),
                token.expireAtEpochMs(), nickname, faceURL, rtcService.rtcForSession(tenantId));
    }

    /**
     * 账号桥供给：未注册则注册；已注册（errCode 1102 RegisteredAlreadyError，PoC 定型）则把
     * mse 侧昵称/头像同步一次——mse 是资料唯一真源（头像生效值含 IM 自设，见 {@link ImProfileService}；
     * 自设头像的变更在 ImProfileService 里即时推送，不经此处）。会话签发与页面分享（{@link ImShareService}）共用。
     */
    public void ensureUser(MseImProperties.Server server, String userID, String nickname, String faceURL) {
        if (!openImClient.userRegisterIfAbsent(server, openImClient.adminToken(server), userID, nickname, faceURL)) {
            openImClient.updateUserProfile(server, openImClient.adminToken(server), userID, nickname, faceURL);
        }
    }

    /**
     * IM 会话凭据（客户端 SDK login 入参）：apiAddr/wsAddr 为该租户实例的公网坐标
     * （同域名反代），expireAt 为 IM token 过期 epoch 毫秒（0 = 实例未返回有效期）；
     * rtc 为音视频通话坐标（enabled=false 客户端隐藏通话入口）。
     */
    public record SessionVO(String userID, String imToken, String apiAddr, String wsAddr,
                            long expireAt, String nickname, String faceURL, ImRtcService.RtcVO rtc) {
    }
}
