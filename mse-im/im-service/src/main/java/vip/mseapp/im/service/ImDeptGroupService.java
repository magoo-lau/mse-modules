package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.api.system.RemoteOrgService;
import vip.mseapp.api.system.dto.OrgDeptDTO;
import vip.mseapp.api.system.dto.OrgUserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImDeptGroup;
import vip.mseapp.im.mapper.ImDeptGroupMapper;
import vip.mseapp.im.support.ImInstanceResolver;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import vip.mseapp.core.concurrent.MseExecutors;
import java.util.stream.Collectors;

/**
 * 部门群（按部门自动创建的 OpenIM 群）：群主=部门负责人（sys_dept.leader_user_id，经
 * RemoteOrgService 契约读取），未设置/已停用回落租户管理员；群成员随部门在职名单对账收敛。
 *
 * <p>触发：用户签发 IM 会话时异步保障本人所在部门群 + 10 分钟防抖全量对账（自愈）；
 * im-ui 可手动全量对账。部门删除 → 解散群并删映射行（部门编号不复用，逻辑删无墓碑撞键风险）。
 */
@Service
public class ImDeptGroupService {

    private static final Logger log = LoggerFactory.getLogger(ImDeptGroupService.class);
    private static final String SYNC_LOCK_PREFIX = "im:deptgroup:sync:";
    private static final Duration SYNC_LOCK_TTL = Duration.ofMinutes(10);
    private static final String BOT_PREFIX = "bot_";
    private static final int MAX_GROUP_NAME = 64;

    /** 部门群保障线程池（daemon：不阻碍停机；任务内全程自捕获异常）。经 MseExecutors 工厂：
     *  有界固定池替代原 cached（无上限线程是隐患）；wrap 池内恢复提交线程租户（提交点 runAs 保留双保险） */
    private final ExecutorService executor = MseExecutors.fixedPool("im-deptgroup", 2, 100);

    @RpcReference
    private RemoteOrgService remoteOrgService;

    private final ImDeptGroupMapper deptGroupMapper;
    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final ImContactService contactService;
    private final StringRedisTemplate redis;

    public ImDeptGroupService(ImDeptGroupMapper deptGroupMapper, ImInstanceResolver instanceResolver,
                              OpenImClient openImClient, ImContactService contactService, StringRedisTemplate redis) {
        this.deptGroupMapper = deptGroupMapper;
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.contactService = contactService;
        this.redis = redis;
    }

    // -------------------- 会话签发钩子（异步，失败仅日志） --------------------

    /**
     * 用户签发 IM 会话后调用：10 分钟防抖全量对账（新建缺失的部门群/补拉新成员，自愈）+
     * 保障本人已在所属部门群。全程异步不阻断会话签发。
     */
    public void ensureForUserAsync(Long tenantId, Long userId, Long deptId) {
        executor.submit(() -> TenantContext.runAs(tenantId, () -> {
            try {
                reconcileDebounced(tenantId);
                ensureMember(tenantId, userId, deptId);
            } catch (Exception e) {
                log.warn("[mse-im] 部门群保障失败: tenant={} user={} err={}", tenantId, userId, e.toString(), e);
            }
        }));
    }

    /** 防抖对账：抢不到锁（窗口内已有实例做过）直接跳过；失败释放锁，下次登录重试 */
    private void reconcileDebounced(Long tenantId) {
        Boolean first = redis.opsForValue().setIfAbsent(SYNC_LOCK_PREFIX + tenantId, "1", SYNC_LOCK_TTL);
        if (first == null || !first) {
            return;
        }
        try {
            reconcileTenant(tenantId);
        } catch (Exception e) {
            redis.delete(SYNC_LOCK_PREFIX + tenantId);
            throw e;
        }
    }

    /** 保障用户已在其部门群（群缺失时先做一轮对账） */
    private void ensureMember(Long tenantId, Long userId, Long deptId) {
        if (deptId == null) {
            return;
        }
        ImDeptGroup row = findByDept(tenantId, deptId);
        if (row == null) {
            reconcileTenant(tenantId);
            row = findByDept(tenantId, deptId);
        }
        if (row == null) {
            return; // 部门无在职用户或对账被防抖跳过，等下次触发
        }
        MseImProperties.Server server = instanceResolver.resolve(tenantId);
        String adminToken = openImClient.adminToken(server);
        if (!openImClient.getGroupMemberUserIds(server, adminToken, row.getGroupId()).contains(String.valueOf(userId))) {
            openImClient.inviteUserToGroup(server, adminToken, row.getGroupId(), List.of(String.valueOf(userId)));
        }
    }

    private ImDeptGroup findByDept(Long tenantId, Long deptId) {
        return deptGroupMapper.selectOne(new LambdaQueryWrapper<ImDeptGroup>()
                .eq(ImDeptGroup::getTenantId, tenantId)
                .eq(ImDeptGroup::getDeptId, deptId)
                .last("limit 1"));
    }

    // -------------------- 全量对账 --------------------

    /** im-ui 手动同步（不防抖，同步执行返回摘要） */
    public ReconcileSummary manualSync() {
        Long tenantId = TenantContext.getRequiredTenantId();
        instanceResolver.resolve(tenantId); // 未开通实例先抛清晰错误
        return reconcileTenant(tenantId);
    }

    /**
     * 全量对账（调用方须已处于租户上下文）：新部门建群、部门改名同步群名、
     * 缺失成员补拉（顺带注册）、多余成员踢出（群主与 bot_ 除外）、部门删除解散、群主随负责人转让。
     */
    public ReconcileSummary reconcileTenant(Long tenantId) {
        MseImProperties.Server server = instanceResolver.resolve(tenantId);
        String adminToken = openImClient.adminToken(server);

        List<OrgDeptDTO> depts = remoteOrgService.listAllDepts();
        List<OrgUserDTO> enabledUsers = contactService.pageAllEnabledUsers();
        Map<Long, List<OrgUserDTO>> usersByDept = new HashMap<>();
        Set<Long> enabledIds = new HashSet<>();
        for (OrgUserDTO user : enabledUsers) {
            enabledIds.add(user.getId());
            if (user.getDeptId() != null) {
                usersByDept.computeIfAbsent(user.getDeptId(), k -> new ArrayList<>()).add(user);
            }
        }
        // 回落群主=租户管理员（须启用）
        Long adminId = remoteOrgService.getTenantAdminUserId();
        Long fallbackOwner = adminId != null && enabledIds.contains(adminId) ? adminId : null;
        // 负责人有效性：须为本域启用用户（负责人可能不在本部门）
        Set<Long> validUsers = depts.stream().map(OrgDeptDTO::getLeaderUserId)
                .filter(Objects::nonNull).filter(enabledIds::contains).collect(Collectors.toSet());

        List<ImDeptGroup> rows = deptGroupMapper.selectList(new LambdaQueryWrapper<ImDeptGroup>()
                .eq(ImDeptGroup::getTenantId, tenantId));
        Map<Long, ImDeptGroup> byDept = rows.stream()
                .collect(Collectors.toMap(ImDeptGroup::getDeptId, r -> r));

        int created = 0, dismissed = 0, added = 0, kicked = 0, transferred = 0, renamed = 0;

        for (OrgDeptDTO dept : depts) {
            try {
                ImDeptGroup row = byDept.get(dept.getId());
                List<OrgUserDTO> users = usersByDept.getOrDefault(dept.getId(), List.of());
                if (row == null) {
                    if (users.isEmpty()) {
                        continue; // 空部门不建群（有人登录/对账时再建）
                    }
                    Long owner = pickOwner(dept, validUsers, fallbackOwner);
                    if (owner == null) {
                        continue;
                    }
                    // 全员先注册（owner 与初始成员都要已在实例内）
                    contactService.ensureUsers(users.stream().map(OrgUserDTO::getId).toList());
                    List<String> memberIDs = users.stream().map(u -> String.valueOf(u.getId()))
                            .filter(id -> !id.equals(String.valueOf(owner))).toList();
                    OpenImClient.GroupInfo group = openImClient.createGroup(server, adminToken,
                            deptGroupName(dept.getName()), String.valueOf(owner), memberIDs);
                    ImDeptGroup entity = new ImDeptGroup();
                    entity.setTenantId(tenantId);
                    entity.setDeptId(dept.getId());
                    entity.setDeptName(dept.getName());
                    entity.setGroupId(group.groupID());
                    entity.setOwnerUserId(owner);
                    deptGroupMapper.insert(entity);
                    created++;
                    continue;
                }
                // 部门改名 → 群名同步
                if (dept.getName() != null && !dept.getName().equals(row.getDeptName())) {
                    openImClient.setGroupName(server, adminToken, row.getGroupId(), deptGroupName(dept.getName()));
                    row.setDeptName(dept.getName());
                    deptGroupMapper.updateById(row);
                    renamed++;
                }
                // 群主对账：负责人变更 → 注册+入群+转让
                Long desiredOwner = pickOwner(dept, validUsers, fallbackOwner);
                if (desiredOwner != null && !desiredOwner.equals(row.getOwnerUserId())) {
                    transferOwner(server, adminToken, row, desiredOwner);
                    transferred++;
                }
                // 成员对账：缺失补拉、多余踢出
                Set<String> actual = new HashSet<>(openImClient.getGroupMemberUserIds(server, adminToken, row.getGroupId()));
                Set<String> expected = users.stream().map(u -> String.valueOf(u.getId())).collect(Collectors.toSet());
                List<Long> missing = expected.stream().filter(id -> !actual.contains(id)).map(Long::valueOf).toList();
                if (!missing.isEmpty()) {
                    contactService.ensureUsers(missing);
                    openImClient.inviteUserToGroup(server, adminToken, row.getGroupId(),
                            missing.stream().map(String::valueOf).toList());
                    added += missing.size();
                }
                List<String> extra = actual.stream()
                        .filter(id -> !expected.contains(id))
                        .filter(id -> !id.equals(String.valueOf(row.getOwnerUserId()))) // 群主不踢
                        .filter(id -> !id.startsWith(BOT_PREFIX))                       // 机器人不踢（群主自管）
                        .toList();
                if (!extra.isEmpty()) {
                    openImClient.kickGroupMembers(server, adminToken, row.getGroupId(), extra);
                    kicked += extra.size();
                }
            } catch (Exception e) {
                log.warn("[mse-im] 部门群对账单部门失败: tenant={} dept={} err={}", tenantId, dept.getId(), e.toString());
            }
        }

        // 部门已删除 → 解散群 + 物理删行（账本唯一键防墓碑）
        Set<Long> activeDeptIds = depts.stream().map(OrgDeptDTO::getId).collect(Collectors.toSet());
        for (ImDeptGroup row : rows) {
            if (activeDeptIds.contains(row.getDeptId())) {
                continue;
            }
            try {
                openImClient.dismissGroup(server, adminToken, row.getGroupId());
            } catch (Exception e) {
                log.warn("[mse-im] 解散已删部门的群失败（继续删映射）: group={} err={}", row.getGroupId(), e.getMessage());
            }
            deptGroupMapper.deleteById(row.getId());
            dismissed++;
        }
        return new ReconcileSummary(created, dismissed, added, kicked, transferred, renamed);
    }

    /** 群主判定：部门负责人（启用）优先，未设置/已停用回落租户管理员 */
    private Long pickOwner(OrgDeptDTO dept, Set<Long> validUsers, Long fallbackOwner) {
        Long leader = dept.getLeaderUserId();
        if (leader != null && validUsers.contains(leader)) {
            return leader;
        }
        return fallbackOwner;
    }

    /** 群主转让：新群主先注册+入群；oldOwner 以 OpenIM 实际群主为准（防 mse 侧记录漂移导致转让失败） */
    private void transferOwner(MseImProperties.Server server, String adminToken, ImDeptGroup row, Long newOwner) {
        contactService.ensureUsers(List.of(newOwner));
        Set<String> members = new HashSet<>(openImClient.getGroupMemberUserIds(server, adminToken, row.getGroupId()));
        if (!members.contains(String.valueOf(newOwner))) {
            openImClient.inviteUserToGroup(server, adminToken, row.getGroupId(), List.of(String.valueOf(newOwner)));
        }
        OpenImClient.GroupInfo info = openImClient.getGroupInfo(server, adminToken, row.getGroupId());
        String actualOwner = info != null && info.ownerUserID() != null && !info.ownerUserID().isBlank()
                ? info.ownerUserID() : String.valueOf(row.getOwnerUserId());
        openImClient.transferGroupOwner(server, adminToken, row.getGroupId(), actualOwner, String.valueOf(newOwner));
        row.setOwnerUserId(newOwner);
        deptGroupMapper.updateById(row);
    }

    /** 部门群群名：「{部门名}群」 */
    private static String deptGroupName(String deptName) {
        String name = (deptName == null || deptName.isBlank() ? "部门" : deptName) + "群";
        return name.length() > MAX_GROUP_NAME ? name.substring(0, MAX_GROUP_NAME) : name;
    }

    /** im-ui 映射列表：映射行 + 群名/成员数（OpenIM 现查，实例不可达的行降级留空） */
    public List<DeptGroupVO> listView() {
        Long tenantId = TenantContext.getRequiredTenantId();
        List<ImDeptGroup> rows = deptGroupMapper.selectList(new LambdaQueryWrapper<ImDeptGroup>()
                .eq(ImDeptGroup::getTenantId, tenantId)
                .orderByAsc(ImDeptGroup::getDeptId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ownerIds = rows.stream().map(ImDeptGroup::getOwnerUserId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> ownerNames = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            for (OrgUserDTO user : remoteOrgService.listUsersByIds(ownerIds)) {
                ownerNames.put(user.getId(), ImContactService.displayName(user.getNickname(), user.getUsername()));
            }
        }
        MseImProperties.Server server = null;
        String adminToken = null;
        try {
            server = instanceResolver.resolve(tenantId);
            adminToken = openImClient.adminToken(server);
        } catch (Exception e) {
            log.warn("[mse-im] 部门群列表取实例失败（降级仅返回映射）: {}", e.getMessage());
        }
        List<DeptGroupVO> out = new ArrayList<>();
        for (ImDeptGroup row : rows) {
            String groupName = null;
            long memberCount = 0;
            if (server != null) {
                try {
                    OpenImClient.GroupInfo info = openImClient.getGroupInfo(server, adminToken, row.getGroupId());
                    if (info != null) {
                        groupName = info.groupName();
                        memberCount = info.memberCount();
                    }
                } catch (Exception ignore) {
                    // 实例不可达/群不存在：降级留空
                }
            }
            out.add(new DeptGroupVO(row.getDeptId(), row.getDeptName(), row.getGroupId(), groupName,
                    row.getOwnerUserId(), ownerNames.get(row.getOwnerUserId()), memberCount));
        }
        return out;
    }

    public record ReconcileSummary(int groupsCreated, int groupsDismissed, int membersAdded,
                                   int membersKicked, int ownersTransferred, int groupsRenamed) {
    }

    public record DeptGroupVO(Long deptId, String deptName, String groupId, String groupName,
                              Long ownerUserId, String ownerName, long memberCount) {
    }
}
