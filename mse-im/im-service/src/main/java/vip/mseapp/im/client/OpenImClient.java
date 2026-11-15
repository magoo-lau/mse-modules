package vip.mseapp.im.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.config.MseImProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenIM Server 平台 API 最小客户端（Java 21 HttpClient，无第三方 SDK，样板 mp-service WxClient）。
 *
 * <p>仅封装 mse-im 用到的接口：get_admin_token / user_register / get_users_info / get_user_token。
 * OpenIM 约定 HTTP 200 时业务错误也在响应体（errCode != 0），调用方以本类转换的 ServiceException 判定。
 * admin token 按实例缓存、临期自动重取；管理调用失败若疑似 token 失效则失效缓存后重试一次。
 */
@Component
public class OpenImClient {

    private static final Logger log = LoggerFactory.getLogger(OpenImClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** OpenIM 默认 admin token 有效期（share.yml 未返回 expireTimeSeconds 时的兜底，90 天） */
    private static final long DEFAULT_ADMIN_TOKEN_TTL_MS = 90L * 24 * 3600 * 1000;

    /** 重复注册错误码（v3.8.3-patch.15 实测：1102 RegisteredAlreadyError） */
    private static final int ERR_ALREADY_REGISTERED = 1102;

    private final MseImProperties properties;
    private final HttpClient http;

    /** admin token 缓存：key = 实例 api-url */
    private final ConcurrentHashMap<String, CachedAdminToken> adminTokens = new ConcurrentHashMap<>();

    private record CachedAdminToken(String token, long expireAtEpochMs) {
    }

    public OpenImClient(MseImProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getClient().getTimeoutMs()))
                .build();
    }

    /** 签发用户 IM token（POST /auth/get_user_token） */
    public UserToken userToken(MseImProperties.Server server, String adminToken, String userID, int platformID) {
        JsonNode data = adminCall(server, adminToken, "/auth/get_user_token",
                Map.of("userID", userID, "platformID", platformID));
        String token = data.path("token").asText("");
        if (token.isBlank()) {
            throw new ServiceException(502, "OpenIM 未返回 IM token");
        }
        long expireSeconds = data.path("expireTimeSeconds").asLong(0);
        return new UserToken(token, expireSeconds > 0 ? System.currentTimeMillis() + expireSeconds * 1000 : 0L);
    }

    public record UserToken(String token, long expireAtEpochMs) {
    }

    /** 注册用户（POST /user/user_register，body {users:[...]}）。返回 false = 用户已注册（errCode 1102 RegisteredAlreadyError，PoC 定型） */
    public boolean userRegisterIfAbsent(MseImProperties.Server server, String adminToken, String userID, String nickname, String faceURL) {
        JsonNode root = postRoot(server, adminToken, "/user/user_register",
                Map.of("users", List.of(buildUserBody(userID, nickname, faceURL))));
        int errCode = root.path("errCode").asInt(0);
        if (errCode == ERR_ALREADY_REGISTERED) {
            return false;
        }
        if (errCode != 0) {
            throw new ServiceException(502, "OpenIM 调用失败：" + root.path("errMsg").asText("errCode=" + errCode));
        }
        return true;
    }

    /** 管理端更新用户资料（POST /user/update_user_info，body {userInfo:{userID,...}}，PoC 定型）：mse 侧昵称/头像为唯一真源 */
    public void updateUserProfile(MseImProperties.Server server, String adminToken, String userID, String nickname, String faceURL) {
        adminCall(server, adminToken, "/user/update_user_info",
                Map.of("userInfo", buildUserBody(userID, nickname, faceURL)));
    }

    private static Map<String, Object> buildUserBody(String userID, String nickname, String faceURL) {
        Map<String, Object> user = new java.util.LinkedHashMap<>();
        user.put("userID", userID);
        user.put("nickname", nickname);
        user.put("faceURL", faceURL == null ? "" : faceURL);
        return user;
    }

    /** 管理端强制下线（POST /auth/force_logout，body 须带 platformID，PoC 定型） */
    public void forceLogout(MseImProperties.Server server, String adminToken, String userID, int platformID) {
        adminCall(server, adminToken, "/auth/force_logout", Map.of("userID", userID, "platformID", platformID));
    }

    /**
     * 管理端代发消息（POST /msg/send_msg，sendID 可为任意已注册用户）。
     *
     * @param sessionType 1 单聊（recvID 必填）/ 3 群聊（groupID 必填）
     * @param contentType 101 文本 / 106 AtText 等
     * @param content     消息体元素（101：{content}；106：{text, atUserList, isAtSelf}）
     * @param senderNickname  发送者昵称（服务端不回填用户资料，缺省时消息端展示只能落 sendID）
     * @param senderFaceURL   发送者头像（同上，可空）
     */
    public void sendMsg(MseImProperties.Server server, String adminToken, String sendID,
                        String recvID, String groupID, int sessionType, int contentType, Map<String, Object> content,
                        String senderNickname, String senderFaceURL) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("sendID", sendID);
        body.put("sessionType", sessionType);
        body.put("contentType", contentType);
        body.put("content", content);
        if (senderNickname != null && !senderNickname.isBlank()) {
            body.put("senderNickname", senderNickname);
        }
        if (senderFaceURL != null && !senderFaceURL.isBlank()) {
            body.put("senderFaceURL", senderFaceURL);
        }
        if (recvID != null && !recvID.isBlank()) {
            body.put("recvID", recvID);
        }
        if (groupID != null && !groupID.isBlank()) {
            body.put("groupID", groupID);
        }
        adminCall(server, adminToken, "/msg/send_msg", body);
    }

    // -------------------- 群组管理端（形态经 v3.8.3-patch.15 实例实测/protocol v0.0.73-alpha.18 源码核对） --------------------

    /** 群角色（roleLevel）：100 群主 / 60 群管理员 / 20 普通成员 */
    public static final int ROLE_OWNER = 100;
    public static final int ROLE_ADMIN = 60;

    /** 群资料（get_groups_info） */
    public record GroupInfo(String groupID, String groupName, String ownerUserID, long memberCount) {
    }

    /** 群成员（get_group_members_info / get_group_member_list） */
    public record GroupMember(String userID, int roleLevel, String nickname, String faceURL) {
    }

    /**
     * 建群（POST /group/create_group）：owner 与初始成员同一请求带入（成员后补会撞
     * 官方已知的建群后立刻 invite 500 问题），群 ID 由 OpenIM 生成。
     * groupType 必须显式传 2（普通群；服务端深层校验只支持 2，缺省 0 报 ArgsError）。
     */
    public GroupInfo createGroup(MseImProperties.Server server, String adminToken, String groupName,
                                 String ownerUserID, List<String> memberUserIDs) {
        JsonNode data = adminCall(server, adminToken, "/group/create_group", Map.of(
                "groupInfo", Map.of("groupName", groupName == null ? "" : groupName, "groupType", 2),
                "ownerUserID", ownerUserID,
                "memberUserIDs", memberUserIDs == null ? List.of() : memberUserIDs));
        JsonNode info = data.path("groupInfo");
        return new GroupInfo(info.path("groupID").asText(""), info.path("groupName").asText(""),
                info.path("ownerUserID").asText(""), info.path("memberCount").asLong(0));
    }

    /** 拉人进群（POST /group/invite_user_to_group）；已在群内的成员由调用方先行过滤（重复邀请 OpenIM 报 mongo 500） */
    public void inviteUserToGroup(MseImProperties.Server server, String adminToken, String groupID, List<String> invitedUserIDs) {
        if (invitedUserIDs == null || invitedUserIDs.isEmpty()) {
            return;
        }
        adminCall(server, adminToken, "/group/invite_user_to_group",
                Map.of("groupID", groupID, "invitedUserIDs", invitedUserIDs, "reason", ""));
    }

    /** 改群名（POST /group/set_group_info，body 包一层 groupInfoForSet） */
    public void setGroupName(MseImProperties.Server server, String adminToken, String groupID, String groupName) {
        adminCall(server, adminToken, "/group/set_group_info",
                Map.of("groupInfoForSet", Map.of("groupID", groupID, "groupName", groupName)));
    }

    /** 转让群主（POST /group/transfer_group）：新群主须已在群内，原群主自动降为成员 */
    public void transferGroupOwner(MseImProperties.Server server, String adminToken, String groupID,
                                   String oldOwnerUserID, String newOwnerUserID) {
        adminCall(server, adminToken, "/group/transfer_group", Map.of(
                "groupID", groupID, "oldOwnerUserID", oldOwnerUserID, "newOwnerUserID", newOwnerUserID));
    }

    /** 踢人（POST /group/kick_group；v3.8 实际路由名，kick_group_member 是 404） */
    public void kickGroupMembers(MseImProperties.Server server, String adminToken, String groupID, List<String> kickedUserIDs) {
        if (kickedUserIDs == null || kickedUserIDs.isEmpty()) {
            return;
        }
        adminCall(server, adminToken, "/group/kick_group",
                Map.of("groupID", groupID, "kickedUserIDs", kickedUserIDs, "reason", ""));
    }

    /** 解散群（POST /group/dismiss_group，deleteMember=true 同时移除成员关系） */
    public void dismissGroup(MseImProperties.Server server, String adminToken, String groupID) {
        adminCall(server, adminToken, "/group/dismiss_group", Map.of("groupID", groupID, "deleteMember", true));
    }

    /** 群资料（POST /group/get_groups_info，单群便捷封装；群不存在返回 null） */
    public GroupInfo getGroupInfo(MseImProperties.Server server, String adminToken, String groupID) {
        JsonNode data = adminCall(server, adminToken, "/group/get_groups_info", Map.of("groupIDs", List.of(groupID)));
        JsonNode infos = data.path("groupInfos");
        if (!infos.isArray() || infos.isEmpty()) {
            return null;
        }
        JsonNode info = infos.get(0);
        return new GroupInfo(info.path("groupID").asText(""), info.path("groupName").asText(""),
                info.path("ownerUserID").asText(""), info.path("memberCount").asLong(0));
    }

    /** 群成员资料（POST /group/get_group_members_info，仅返回在群内的成员） */
    public List<GroupMember> getGroupMembersInfo(MseImProperties.Server server, String adminToken, String groupID, List<String> userIDs) {
        if (userIDs == null || userIDs.isEmpty()) {
            return List.of();
        }
        JsonNode data = adminCall(server, adminToken, "/group/get_group_members_info",
                Map.of("groupID", groupID, "userIDs", userIDs));
        return toMembers(data.path("members"));
    }

    /** 全量群成员编号（POST /group/get_group_member_list 分页聚合） */
    public List<String> getGroupMemberUserIds(MseImProperties.Server server, String adminToken, String groupID) {
        List<String> all = new java.util.ArrayList<>();
        int pageNumber = 1;
        while (true) {
            JsonNode data = adminCall(server, adminToken, "/group/get_group_member_list", Map.of(
                    "groupID", groupID, "filter", 0,
                    "pagination", Map.of("pageNumber", pageNumber, "showNumber", 200)));
            List<GroupMember> members = toMembers(data.path("members"));
            members.forEach(m -> all.add(m.userID()));
            if (members.size() < 200) {
                return all;
            }
            pageNumber++;
        }
    }

    private static List<GroupMember> toMembers(JsonNode members) {
        if (!members.isArray()) {
            return List.of();
        }
        List<GroupMember> out = new java.util.ArrayList<>();
        for (JsonNode m : members) {
            out.add(new GroupMember(m.path("userID").asText(""), m.path("roleLevel").asInt(20),
                    m.path("nickname").asText(""), m.path("faceURL").asText("")));
        }
        return out;
    }

    /** admin token：按实例缓存，剩余有效期低于刷新阈值即重取（POST /auth/get_admin_token，接口在白名单无需既有 token） */    public String adminToken(MseImProperties.Server server) {
        long now = System.currentTimeMillis();
        CachedAdminToken cached = adminTokens.get(server.getApiUrl());
        if (cached != null && now < freshUntil(cached)) {
            return cached.token();
        }
        synchronized (this) {
            cached = adminTokens.get(server.getApiUrl());
            if (cached != null && now < freshUntil(cached)) {
                return cached.token();
            }
            JsonNode data = checkErr(postRoot(server, null, "/auth/get_admin_token",
                    Map.of("secret", server.getSecret(), "userID", server.getAdminUserId())), "/auth/get_admin_token");
            String token = data.path("token").asText("");
            if (token.isBlank()) {
                throw new ServiceException(502, "OpenIM 未返回 admin token");
            }
            long expireSeconds = data.path("expireTimeSeconds").asLong(0);
            long expireAt = now + (expireSeconds > 0 ? expireSeconds * 1000 : DEFAULT_ADMIN_TOKEN_TTL_MS);
            adminTokens.put(server.getApiUrl(), new CachedAdminToken(token, expireAt));
            return token;
        }
    }

    /** 携 admin token 的管理调用：失败信息疑似 token 失效时，失效缓存重取后重试一次 */
    private JsonNode adminCall(MseImProperties.Server server, String adminToken, String path, Map<String, ?> body) {
        try {
            JsonNode root = postRoot(server, adminToken, path, body);
            return checkErr(root, path);
        } catch (ServiceException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!message.contains("token")) {
                throw e;
            }
            log.info("[mse-im] OpenIM admin token 疑似失效，重取后重试: url={} path={}", server.getApiUrl(), path);
            adminTokens.remove(server.getApiUrl());
            JsonNode root = postRoot(server, adminToken(server), path, body);
            return checkErr(root, path);
        }
    }

    private JsonNode checkErr(JsonNode root, String path) {
        int errCode = root.path("errCode").asInt(0);
        if (errCode != 0) {
            throw new ServiceException(502, "OpenIM 调用失败：" + root.path("errMsg").asText("errCode=" + errCode));
        }
        return root.path("data");
    }

    private long freshUntil(CachedAdminToken cached) {
        return cached.expireAtEpochMs() - properties.getClient().getAdminTokenRefreshAheadSeconds() * 1000L;
    }

    /** 发起 POST 并返回完整响应根（errCode 检查由调用方按语义执行，如 1102 已注册短路） */
    private JsonNode postRoot(MseImProperties.Server server, String token, String path, Map<String, ?> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getApiUrl() + path))
                    .timeout(Duration.ofMillis(properties.getClient().getTimeoutMs()))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .header("operationID", UUID.randomUUID().toString().replace("-", ""))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            if (token != null) {
                builder.header("token", token);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return MAPPER.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(500, "OpenIM 调用被中断");
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[mse-im] OpenIM API 调用失败: url={} path={}", server.getApiUrl(), path, e);
            throw new ServiceException(502, "OpenIM 网络调用失败：" + e.getMessage());
        }
    }
}
