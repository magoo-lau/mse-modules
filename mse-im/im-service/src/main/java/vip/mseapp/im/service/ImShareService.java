package vip.mseapp.im.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.support.ImInstanceResolver;
import vip.mseapp.security.stp.StpTenantUtil;

/**
 * 页面分享：登录态用户把基座页面卡片（app-share 链接卡片，custom 110）发送给联系人/群聊。
 *
 * <p>以当前用户身份经管理端代发（OpenIM 服务端不回填用户资料，昵称/头像随消息下发，
 * 同 {@link ImBotService} 口径）；发送人与用户收件人未注册 IM 时兜底注册，
 * 群目标校验发送人在群内（防越权向任意群投递）。卡片 payload 与聊天客户端
 * clients/web chat/custom.ts 的 app-share 协议对齐，收件人点击经工作台 SSO 免登直达。
 */
@Service
public class ImShareService {

    /** OpenIM contentType：110 custom；sessionType：1 单聊 / 3 群聊 */
    private static final int CONTENT_TYPE_CUSTOM = 110;
    private static final int SESSION_SINGLE = 1;
    private static final int SESSION_GROUP = 3;
    private static final String CUSTOM_TYPE_APP_SHARE = "app-share";
    private static final int MAX_USER_TARGETS = 50;
    private static final int MAX_GROUP_TARGETS = 20;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @RpcReference
    private RemoteUserService remoteUserService;

    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final ImSessionService sessionService;
    private final ImContactService contactService;
    private final ImProfileService profileService;

    public ImShareService(ImInstanceResolver instanceResolver, OpenImClient openImClient,
                          ImSessionService sessionService, ImContactService contactService,
                          ImProfileService profileService) {
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.sessionService = sessionService;
        this.contactService = contactService;
        this.profileService = profileService;
    }

    /** 发送页面卡片，返回成功送达目标数；任一目标失败即抛出（带目标上下文，已送达的不回滚）。
     *  icon 为卡片图标（emoji/字符或图片地址，可空）——随卡片 payload 透传，收件人按同款规则渲染 */
    public int share(List<Long> userIds, List<String> groupIds, String title, String desc, String url, String icon) {
        boolean hasUsers = userIds != null && !userIds.isEmpty();
        boolean hasGroups = groupIds != null && !groupIds.isEmpty();
        if (!hasUsers && !hasGroups) {
            throw new ServiceException(400, "请选择分享目标");
        }
        if (hasUsers && userIds.size() > MAX_USER_TARGETS) {
            throw new ServiceException(400, "联系人最多 " + MAX_USER_TARGETS + " 位");
        }
        if (hasGroups && groupIds.size() > MAX_GROUP_TARGETS) {
            throw new ServiceException(400, "群聊最多 " + MAX_GROUP_TARGETS + " 个");
        }
        parseUrl(url);
        Long tenantId = StpTenantUtil.getTenantIdFromLoginId();
        Long userId = StpTenantUtil.getUserIdFromLoginId();
        MseImProperties.Server server = instanceResolver.resolve(tenantId);
        String adminToken = openImClient.adminToken(server);
        UserDTO sender = remoteUserService.getUserById(userId);
        // SysUser.status：0 启用 1 停用（仓库惯例）
        if (sender == null || sender.getStatus() == null || sender.getStatus() != 0) {
            throw new ServiceException(403, "用户不存在或已停用");
        }
        String senderID = String.valueOf(userId);
        String nickname = sender.getNickname() == null || sender.getNickname().isBlank() ? sender.getUsername() : sender.getNickname();
        String faceURL = profileService.resolveFaceUrl(tenantId, userId, sender.getAvatar());
        sessionService.ensureUser(server, senderID, nickname, faceURL);
        if (hasUsers) {
            contactService.ensureUsers(userIds);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("data", MAPPER.writeValueAsString(new AppShareCard(CUSTOM_TYPE_APP_SHARE, title, desc, url,
                icon == null || icon.isBlank() ? null : icon)));
        content.put("extension", "");
        content.put("description", title);
        int sent = 0;
        if (hasUsers) {
            for (Long recvId : userIds) {
                try {
                    openImClient.sendMsg(server, adminToken, senderID, String.valueOf(recvId), null,
                            SESSION_SINGLE, CONTENT_TYPE_CUSTOM, content, nickname, faceURL);
                    sent++;
                } catch (Exception e) {
                    throw new ServiceException(502, "发送给用户 " + recvId + " 失败：" + e.getMessage());
                }
            }
        }
        if (hasGroups) {
            for (String groupId : groupIds) {
                ensureGroupMember(server, adminToken, senderID, groupId);
                try {
                    openImClient.sendMsg(server, adminToken, senderID, null, groupId,
                            SESSION_GROUP, CONTENT_TYPE_CUSTOM, content, nickname, faceURL);
                    sent++;
                } catch (ServiceException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ServiceException(502, "发送到群 " + groupId + " 失败：" + e.getMessage());
                }
            }
        }
        return sent;
    }

    /** 群目标权限：发送人必须在群内（get_group_members_info 仅返回在群成员，空即非成员） */
    private void ensureGroupMember(MseImProperties.Server server, String adminToken, String senderID, String groupId) {
        if (openImClient.getGroupMembersInfo(server, adminToken, groupId, List.of(senderID)).isEmpty()) {
            throw new ServiceException(403, "你不在这个群里，无法分享：" + groupId);
        }
    }

    /** 卡片深链必须是绝对 http(s) 地址（口径同 {@link ImSsoCodeService} 的 targetUrl） */
    private static void parseUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(400, "分享链接非法");
        }
        if ((!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                || uri.getRawAuthority() == null || uri.getRawAuthority().isBlank()) {
            throw new ServiceException(400, "分享链接必须为绝对 http(s) 地址");
        }
    }

    /** app-share 卡片 payload（customElem.data 的 JSON，与客户端 chat/custom.ts 对齐）；icon 可空，空值不序列化 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AppShareCard(String customType, String title, String desc, String url, String icon) {
    }
}
