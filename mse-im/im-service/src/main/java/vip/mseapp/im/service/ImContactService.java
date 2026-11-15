package vip.mseapp.im.service;

import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import vip.mseapp.api.system.RemoteOrgService;
import vip.mseapp.api.system.dto.OrgUserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImDeptGroup;
import vip.mseapp.im.mapper.ImDeptGroupMapper;
import vip.mseapp.im.support.ImInstanceResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 聊天客户端通讯录：部门树 + 启用用户 + 启用机器人一次拉全（数据经基座 RPC，不直连基座表），
 * 并提供「按需注册」——对未登录过 IM 的用户先注册进租户实例，才能被发起单聊/拉进群。
 *
 * <p>用户清单走 RemoteOrgService（组织契约，无数据权限过滤）——通讯录/部门群对账必须看到
 * 全租户在职名单，不能被发起人本人的数据权限范围截断。
 */
@Service
public class ImContactService {

    /** 分页拉全用户的页大小 */
    private static final long PAGE_SIZE = 500;

    @RpcReference
    private RemoteOrgService remoteOrgService;

    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final ImBotService botService;
    private final ImDeptGroupMapper deptGroupMapper;
    private final ImProfileService profileService;

    public ImContactService(ImInstanceResolver instanceResolver, OpenImClient openImClient, ImBotService botService,
                            ImDeptGroupMapper deptGroupMapper, ImProfileService profileService) {
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.botService = botService;
        this.deptGroupMapper = deptGroupMapper;
        this.profileService = profileService;
    }

    /** 通讯录全量（当前租户）：depts 全部部门、users 启用用户、bots 启用机器人、deptGroupIds 部门群 id 集 */
    public ContactVO contacts() {
        List<Dept> depts = remoteOrgService.listAllDepts().stream()
                .map(d -> new Dept(d.getId(), d.getParentId(), d.getName(), d.getLeaderUserId()))
                .toList();
        List<OrgUserDTO> enabledUsers = pageAllEnabledUsers();
        // 生效头像批量三态解析（IM 自设 > 基座 avatar 原样透传，站内相对路径由客户端 assetUrl 解析）
        Map<Long, String> faceOverrides = profileService.resolveFaceUrlOverrides(TenantContext.getRequiredTenantId(),
                enabledUsers.stream().map(OrgUserDTO::getId).toList());
        List<User> users = enabledUsers.stream()
                .map(u -> new User(u.getId(), displayName(u.getNickname(), u.getUsername()),
                        faceOverrides.containsKey(u.getId())
                                ? faceOverrides.get(u.getId()) : blankToNull(u.getAvatar()),
                        u.getDeptId()))
                .toList();
        List<Bot> bots = botService.list().stream()
                .filter(b -> b.getStatus() == null || b.getStatus() == 0)
                .map(b -> new Bot(b.getBotUserId(), b.getNickname(), blankToNull(b.getFaceUrl()), b.getAgentCode()))
                .toList();
        // 部门群 id 集：客户端据此隐藏「退群/解散」入口（部门群由对账自动维护，退了会被拉回）
        List<String> deptGroupIds = deptGroupMapper.selectList(new LambdaQueryWrapper<ImDeptGroup>()
                        .eq(ImDeptGroup::getTenantId, TenantContext.getRequiredTenantId()))
                .stream().map(ImDeptGroup::getGroupId).toList();
        return new ContactVO(depts, users, bots, deptGroupIds);
    }

    /** 把用户注册进租户实例并同步资料（幂等；停用/不存在用户跳过）。返回实际处理数量 */
    public int ensureUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        Set<Long> wanted = new HashSet<>(userIds);
        List<OrgUserDTO> users = pageAllEnabledUsers().stream()
                .filter(u -> wanted.contains(u.getId())).toList();
        if (users.isEmpty()) {
            return 0;
        }
        MseImProperties.Server server = instanceResolver.resolve(TenantContext.getRequiredTenantId());
        String adminToken = openImClient.adminToken(server);
        Map<Long, String> faceOverrides = profileService.resolveFaceUrlOverrides(TenantContext.getRequiredTenantId(),
                users.stream().map(OrgUserDTO::getId).toList());
        int count = 0;
        for (OrgUserDTO user : users) {
            String userID = String.valueOf(user.getId());
            String nickname = displayName(user.getNickname(), user.getUsername());
            String faceURL = faceOverrides.containsKey(user.getId())
                    ? faceOverrides.get(user.getId()) : blankToNull(user.getAvatar());
            if (!openImClient.userRegisterIfAbsent(server, adminToken, userID, nickname, faceURL)) {
                openImClient.updateUserProfile(server, adminToken, userID, nickname, faceURL);
            }
            count++;
        }
        return count;
    }

    /** 当前租户全部启用用户（分页拉全，status 客户端过滤；共享给部门群对账使用） */
    public List<OrgUserDTO> pageAllEnabledUsers() {
        List<OrgUserDTO> all = new ArrayList<>();
        long pageNum = 1;
        while (true) {
            var page = remoteOrgService.pageUsers(pageNum, PAGE_SIZE, null, null);
            all.addAll(page.records());
            if (page.records().size() < PAGE_SIZE) {
                return all.stream().filter(u -> u.getStatus() != null && u.getStatus() == 0).toList();
            }
            pageNum++;
        }
    }

    /** 昵称空回落用户名 */
    static String displayName(String nickname, String username) {
        return nickname == null || nickname.isBlank() ? username : nickname;
    }

    /** 空串归一为 null（头像/资料字段统一口径；站内相对路径原样透传，解析移交客户端） */
    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 通讯录视图（deptGroupIds=本租户全部部门群 groupId，客户端隐藏对应管理入口） */
    public record ContactVO(List<Dept> depts, List<User> users, List<Bot> bots, List<String> deptGroupIds) {
    }

    public record Dept(Long id, Long parentId, String name, Long leaderUserId) {
    }

    public record User(Long userId, String nickname, String avatar, Long deptId) {
    }

    public record Bot(String botUserId, String nickname, String faceUrl, String agentCode) {
    }

    public record EnsureRequest(List<Long> userIds) {
    }
}
