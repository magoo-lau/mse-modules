package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.api.system.RemoteOrgService;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.OrgDeptDTO;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.client.OpenImClient;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImUserProfile;
import vip.mseapp.im.mapper.ImUserProfileMapper;
import vip.mseapp.im.support.ImInstanceResolver;
import vip.mseapp.security.stp.StpTenantUtil;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天客户端用户资料：资料卡查询（基座 RPC 拼装）+ IM 独立头像三态解析与自设。
 *
 * <p>头像解析优先级：IM 自设（im_user_profile.face_url 非空）&gt; 基座 sys_user.avatar
 * （原样透传，含 /fs/asset、/im/client/asset 等站内相对路径——解析移交客户端 assetUrl
 * 加 /api 前缀）&gt; 客户端首字母兜底。自设/重置即时推送 OpenIM（updateUserProfile），
 * 他端消息气泡 senderFaceUrl 随之同步；mse 仍是资料唯一真源，昵称等其余资料不开放 IM 侧自改。
 */
@Service
public class ImProfileService {

    private static final Logger log = LoggerFactory.getLogger(ImProfileService.class);

    /** 部门路径上溯深度上限（防脏数据成环） */
    private static final int MAX_DEPT_DEPTH = 16;

    @RpcReference
    private RemoteUserService remoteUserService;

    @RpcReference
    private RemoteOrgService remoteOrgService;

    private final ImUserProfileMapper userProfileMapper;
    private final ImInstanceResolver instanceResolver;
    private final OpenImClient openImClient;
    private final ImAvatarStorage avatarStorage;

    public ImProfileService(ImUserProfileMapper userProfileMapper, ImInstanceResolver instanceResolver,
                            OpenImClient openImClient, ImAvatarStorage avatarStorage) {
        this.userProfileMapper = userProfileMapper;
        this.instanceResolver = instanceResolver;
        this.openImClient = openImClient;
        this.avatarStorage = avatarStorage;
    }

    /** 资料卡（用户）：基座资料 + 部门路径 + 头像三态解析（仅启用用户，停用不可查） */
    public ProfileVO profile(Long userId) {
        UserDTO user = remoteUserService.getUserById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 0) {
            throw new ServiceException(404, "用户不存在或已停用");
        }
        return new ProfileVO(userId, user.getUsername(),
                ImContactService.displayName(user.getNickname(), user.getUsername()),
                resolveFaceUrl(TenantContext.getRequiredTenantId(), userId, user.getAvatar()),
                user.getMobile(), user.getEmail(), deptPath(user.getDeptId()));
    }

    /** 单用户头像三态解析：IM 自设 > 基座 avatar（原样透传）> null（客户端首字母兜底） */
    public String resolveFaceUrl(Long tenantId, Long userId, String baseAvatar) {
        ImUserProfile row = userProfileMapper.selectOne(new LambdaQueryWrapper<ImUserProfile>()
                .eq(ImUserProfile::getTenantId, tenantId)
                .eq(ImUserProfile::getUserId, userId)
                .last("limit 1"));
        if (row != null && row.getFaceUrl() != null && !row.getFaceUrl().isBlank()) {
            return row.getFaceUrl();
        }
        return blankToNull(baseAvatar);
    }

    /** 批量头像解析（通讯录全量渲染用）：按租户直查小表（每用户至多一行，仅自设过头像才有行）防大 IN 与 N+1 */
    public Map<Long, String> resolveFaceUrlOverrides(Long tenantId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> result = new HashMap<>();
        for (ImUserProfile row : userProfileMapper.selectList(new LambdaQueryWrapper<ImUserProfile>()
                .eq(ImUserProfile::getTenantId, tenantId))) {
            if (userIds.contains(row.getUserId()) && row.getFaceUrl() != null && !row.getFaceUrl().isBlank()) {
                result.put(row.getUserId(), row.getFaceUrl());
            }
        }
        return result;
    }

    /** 上传自设头像（当前登录用户），返回生效头像地址 */
    public String uploadAvatar(MultipartFile file) {
        return saveFaceUrl(avatarStorage.upload(file));
    }

    /** 重置自设头像（当前登录用户）：face_url 置空回落基座头像，返回生效头像地址（null=客户端首字母兜底） */
    public String resetAvatar() {
        return saveFaceUrl("");
    }

    /** 落库（惰性建行/覆写，重置置空不删行）并即时推送 OpenIM，返回生效头像地址 */
    private String saveFaceUrl(String faceUrl) {
        Long tenantId = TenantContext.getRequiredTenantId();
        Long userId = StpTenantUtil.getUserIdFromLoginId();
        ImUserProfile row = userProfileMapper.selectOne(new LambdaQueryWrapper<ImUserProfile>()
                .eq(ImUserProfile::getTenantId, tenantId)
                .eq(ImUserProfile::getUserId, userId)
                .last("limit 1"));
        if (row == null) {
            row = new ImUserProfile();
            row.setTenantId(tenantId);
            row.setUserId(userId);
            row.setFaceUrl(faceUrl);
            userProfileMapper.insert(row);
        } else {
            row.setFaceUrl(faceUrl);
            userProfileMapper.updateById(row);
        }
        String effective = resolveFaceUrl(tenantId, userId, baseAvatar(userId));
        pushToOpenIm(tenantId, userId, effective);
        return effective;
    }

    /**
     * 推送 OpenIM 实例侧用户资料（昵称 + 生效头像），他端消息气泡头像随之同步。
     * 尽力而为：mse 侧落库成功即头像已生效，实例瞬断/不可达只告警不回滚——
     * 否则 OpenIM 故障期改头像会收到 500，而实际早已保存成功，重试也无法消除。
     */
    private void pushToOpenIm(Long tenantId, Long userId, String faceUrl) {
        try {
            MseImProperties.Server server = instanceResolver.resolve(tenantId);
            UserDTO user = remoteUserService.getUserById(userId);
            String nickname = user == null ? String.valueOf(userId)
                    : ImContactService.displayName(user.getNickname(), user.getUsername());
            openImClient.updateUserProfile(server, openImClient.adminToken(server),
                    String.valueOf(userId), nickname, faceUrl);
        } catch (Exception e) {
            log.warn("[mse-im] OpenIM 用户资料推送失败（头像已生效，实例恢复后他端气泡随下次推送收敛）: tenantId={}, userId={}",
                    tenantId, userId, e);
        }
    }

    private String baseAvatar(Long userId) {
        UserDTO user = remoteUserService.getUserById(userId);
        return user == null ? null : blankToNull(user.getAvatar());
    }

    /** 部门路径（根 → 直属，" / " 连接；无部门返回 null；visited 防脏数据成环） */
    private String deptPath(Long deptId) {
        if (deptId == null) {
            return null;
        }
        Map<Long, OrgDeptDTO> byId = new HashMap<>();
        for (OrgDeptDTO dept : remoteOrgService.listAllDepts()) {
            byId.put(dept.getId(), dept);
        }
        List<String> chain = new java.util.ArrayList<>();
        java.util.Set<Long> visited = new java.util.HashSet<>();
        Long cursor = deptId;
        while (cursor != null && byId.containsKey(cursor) && visited.add(cursor) && chain.size() < MAX_DEPT_DEPTH) {
            chain.add(0, byId.get(cursor).getName());
            cursor = byId.get(cursor).getParentId();
        }
        return chain.isEmpty() ? null : String.join(" / ", chain);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 资料卡视图（avatar 为生效头像地址，null=客户端首字母兜底；deptPath 根 → 直属） */
    public record ProfileVO(Long userId, String username, String nickname, String avatar,
                            String mobile, String email, String deptPath) {
    }
}
