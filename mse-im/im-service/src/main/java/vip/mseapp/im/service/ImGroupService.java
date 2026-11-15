package vip.mseapp.im.service;

import org.springframework.stereotype.Service;
import vip.mseapp.api.system.RemoteOrgService;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.OrgUserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImBot;
import vip.mseapp.im.mapper.ImBotMapper;
import vip.mseapp.im.support.ImInstanceResolver;
import vip.mseapp.security.stp.StpTenantUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 群组管理（登录态客户端入口，落 OpenIM 管理端 API）：建群（群主=当前用户）、
 * 邀请进群（仅群主/群管理员）。机器人进群即自动追加其群白名单（幂等），保证拉进来就能用。
 */
@Service
public class ImGroupService {

    private static final int MAX_GROUP_NAME = 64;

    @RpcReference
    private RemoteUserService remoteUserService;

    @RpcReference
    private RemoteOrgService remoteOrgService;

    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final ImBotService botService;
    private final ImBotMapper botMapper;
    private final ImContactService contactService;

    public ImGroupService(ImInstanceResolver instanceResolver, OpenImClient openImClient,
                          ImBotService botService, ImBotMapper botMapper, ImContactService contactService) {
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.botService = botService;
        this.botMapper = botMapper;
        this.contactService = contactService;
    }

    /** 建群：群主=当前用户，成员与机器人同一请求带入（规避 OpenIM 建群后立刻 invite 的已知 500） */
    public Created create(GroupCreateRequest request) {
        Long selfId = StpTenantUtil.getUserIdFromLoginId();
        List<Long> memberIds = new ArrayList<>(distinct(request == null ? null : request.memberUserIds()));
        List<String> botUserIds = distinctStr(request == null ? null : request.botUserIds());
        memberIds.remove(selfId);
        if (memberIds.isEmpty() && botUserIds.isEmpty()) {
            throw new ServiceException(400, "至少选择一位成员或机器人");
        }
        List<ImBot> bots = validateBots(botUserIds);
        contactService.ensureUsers(memberIds);

        MseImProperties.Server server = instanceResolver.resolve(TenantContext.getRequiredTenantId());
        String adminToken = openImClient.adminToken(server);
        String owner = String.valueOf(selfId);
        List<String> memberIDs = new ArrayList<>(memberIds.stream().map(String::valueOf).toList());
        bots.forEach(b -> memberIDs.add(b.getBotUserId()));
        memberIDs.removeIf(owner::equals);
        String groupName = resolveGroupName(request.name(), selfId, memberIds);
        OpenImClient.GroupInfo group = openImClient.createGroup(server, adminToken, groupName, owner, memberIDs);
        appendBotGroups(server, adminToken, bots, group.groupID());
        return new Created(group.groupID(), group.groupName());
    }

    /** 邀请进群：拉人与拉机器人均仅群主/群管理员可操作（服务端按 OpenIM 群角色强制校验） */
    public void invite(InviteRequest request) {
        if (request == null || request.groupId() == null || request.groupId().isBlank()) {
            throw new ServiceException(400, "缺少群编号");
        }
        Long selfId = StpTenantUtil.getUserIdFromLoginId();
        List<Long> memberIds = distinct(request.userIds());
        List<String> botUserIds = distinctStr(request.botUserIds());
        List<ImBot> bots = validateBots(botUserIds);

        MseImProperties.Server server = instanceResolver.resolve(TenantContext.getRequiredTenantId());
        String adminToken = openImClient.adminToken(server);
        // 群角色校验（成员/机器人角色一致，一次查询）
        List<OpenImClient.GroupMember> mine = openImClient.getGroupMembersInfo(server, adminToken,
                request.groupId(), List.of(String.valueOf(selfId)));
        if (mine.isEmpty()) {
            throw new ServiceException(403, "仅群成员可邀请");
        }
        int role = mine.get(0).roleLevel();
        if (role != OpenImClient.ROLE_OWNER && role != OpenImClient.ROLE_ADMIN) {
            throw new ServiceException(403, "仅群主/群管理员可邀请");
        }
        // 已在群内的过滤（重复邀请 OpenIM 直接报错）
        Set<String> existing = new HashSet<>(openImClient.getGroupMemberUserIds(server, adminToken, request.groupId()));
        List<Long> newUsers = memberIds.stream().filter(id -> !existing.contains(String.valueOf(id))).toList();
        contactService.ensureUsers(newUsers);
        List<String> inviteIDs = new ArrayList<>(newUsers.stream().map(String::valueOf).toList());
        bots.forEach(b -> {
            if (!existing.contains(b.getBotUserId())) {
                inviteIDs.add(b.getBotUserId());
            }
        });
        if (inviteIDs.isEmpty()) {
            throw new ServiceException(400, "所选成员均已在群内");
        }
        openImClient.inviteUserToGroup(server, adminToken, request.groupId(), inviteIDs);
        appendBotGroups(server, adminToken, bots, request.groupId());
    }

    /** 机器人白名单校验：必须为本租户启用中的机器人 */
    private List<ImBot> validateBots(List<String> botUserIds) {
        if (botUserIds.isEmpty()) {
            return List.of();
        }
        Map<String, ImBot> enabled = botService.list().stream()
                .filter(b -> b.getStatus() == null || b.getStatus() == 0)
                .collect(Collectors.toMap(ImBot::getBotUserId, b -> b));
        for (String id : botUserIds) {
            if (!enabled.containsKey(id)) {
                throw new ServiceException(400, "机器人不存在或已停用：" + id);
            }
        }
        return botUserIds.stream().map(enabled::get).toList();
    }

    /** 机器人进群即追加群白名单（groupIds 空 = 所有群本就放行，无需写；已有则幂等追加） */
    private void appendBotGroups(MseImProperties.Server server, String adminToken, List<ImBot> bots, String groupId) {
        for (ImBot bot : bots) {
            String current = bot.getGroupIds();
            if (current == null || current.isBlank()) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (String id : current.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    ids.add(trimmed);
                }
            }
            if (ids.contains(groupId)) {
                continue;
            }
            ids.add(groupId);
            bot.setGroupIds(String.join(",", ids));
            botMapper.updateById(bot);
        }
    }

    /** 群名：显式传入优先；缺省「xx、yy的群聊」（群主+前两个成员昵称） */
    private String resolveGroupName(String name, Long selfId, List<Long> memberIds) {
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            if (trimmed.length() > MAX_GROUP_NAME) {
                throw new ServiceException(400, "群名过长（不超过 " + MAX_GROUP_NAME + " 字）");
            }
            return trimmed;
        }
        List<Long> ids = new ArrayList<>();
        ids.add(selfId);
        ids.addAll(memberIds.subList(0, Math.min(2, memberIds.size())));
        StringBuilder sb = new StringBuilder();
        if (!ids.isEmpty()) {
            for (OrgUserDTO user : remoteOrgService.listUsersByIds(ids)) {
                if (!sb.isEmpty()) {
                    sb.append("、");
                }
                sb.append(ImContactService.displayName(user.getNickname(), user.getUsername()));
            }
            sb.append("的群聊");
        } else {
            sb.append("群聊");
        }
        String result = sb.toString();
        return result.length() > MAX_GROUP_NAME ? result.substring(0, MAX_GROUP_NAME) : result;
    }

    private static List<Long> distinct(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && id > 0).distinct().toList();
    }

    private static List<String> distinctStr(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
    }

    /** 建群请求（name 缺省按成员昵称生成） */
    public record GroupCreateRequest(String name, List<Long> memberUserIds, List<String> botUserIds) {
    }

    /** 邀请请求（用户与机器人可混选） */
    public record InviteRequest(String groupId, List<Long> userIds, List<String> botUserIds) {
    }

    public record Created(String groupId, String groupName) {
    }
}
