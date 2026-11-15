package vip.mseapp.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.api.system.RemoteRoleService;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.RoleDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.config.MseImProperties;
import vip.mseapp.im.entity.ImWorkbenchItem;
import vip.mseapp.im.mapper.ImWorkbenchItemMapper;
import vip.mseapp.im.support.ImWebBaseResolver;
import vip.mseapp.im.support.WbSec;
import vip.mseapp.open.api.OpenAppBrief;
import vip.mseapp.security.stp.StpTenantUtil;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作台入口配置：mse 页面菜单入口 + 外部应用入口（免登授权，微信式授权码跳转）。
 *
 * <p>view() 供客户端拉取（仅启用行；mse 菜单入口由客户端以 /system/profile/me 菜单集过滤，
 * 外部应用入口按 role_ids 角色绑定过滤——NULL/空=全员可见，非空=与本人角色有交集才可见；
 * 未配置返回空 → 客户端回退「全量 type=3 菜单 + 默认图标」），all=true 供配置页拉取全量（含停用、不过滤角色）。
 * save 覆盖式保存（全删全插）；免登授权入口必须关联开放平台应用（open_app_id，凭证与
 * 域名白名单归口 mse-open open_app，本服务不落任何密钥）。jump() 为外部应用入口签发授权码
 * （一次性 + 短时效 + 限频 + 入口域名校验 + 角色可见性复核，防绕过客户端直调），
 * 对方服务端经 /im/oauth/token 用 app_key + app_secret 兑换 access_token（凭证校验经 RPC
 * 到开放平台，见 {@link ImOauthService}）。
 */
@Service
public class ImWorkbenchService {

    /** 入口类型：mse 页面菜单 */
    public static final int ENTRY_TYPE_MENU = 0;
    /** 入口类型：外部应用 */
    public static final int ENTRY_TYPE_EXTERNAL = 1;
    /** 授权方式：直连（不携带身份） */
    public static final int AUTH_MODE_NONE = 0;
    /** 授权方式：免登授权（授权码换 access_token，凭证归口开放平台应用） */
    public static final int AUTH_MODE_OAUTH = 1;

    private static final String AUTH_CODE_KEY_PREFIX = "im:wb:authcode:";
    private static final String RATE_LIMIT_KEY_PREFIX = "im:wb:rl:";
    private static final long RATE_LIMIT_WINDOW_SECONDS = 60;

    private final ImWorkbenchItemMapper itemMapper;
    private final ImWebBaseResolver webBaseResolver;
    private final MseImProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectProvider<OpenPlatformGateway> openAppGateway;

    @RpcReference
    private RemoteRoleService remoteRoleService;

    @RpcReference
    private RemoteUserService remoteUserService;

    public ImWorkbenchService(ImWorkbenchItemMapper itemMapper, ImWebBaseResolver webBaseResolver,
                              MseImProperties properties, StringRedisTemplate redis,
                              ObjectProvider<OpenPlatformGateway> openAppGateway) {
        this.itemMapper = itemMapper;
        this.webBaseResolver = webBaseResolver;
        this.properties = properties;
        this.redis = redis;
        this.openAppGateway = openAppGateway;
    }

    /** 视图：跳转基址（台账行 > 全局 mse.im.web-base-url > 内置缺省）+ 入口项；all=true 返回含停用行（配置页） */
    public WorkbenchVO view(boolean all) {
        LambdaQueryWrapper<ImWorkbenchItem> wrapper = new LambdaQueryWrapper<ImWorkbenchItem>()
                .eq(ImWorkbenchItem::getTenantId, TenantContext.getRequiredTenantId())
                .orderByAsc(ImWorkbenchItem::getSort)
                .orderByAsc(ImWorkbenchItem::getId);
        if (!all) {
            wrapper.eq(ImWorkbenchItem::getStatus, 0);
        }
        List<ImWorkbenchItem> items = itemMapper.selectList(wrapper);
        if (!all) {
            items = filterByRole(items);
        }
        return new WorkbenchVO(resolveWebBase(), items);
    }

    /**
     * 角色可见性过滤：未绑定角色（role_ids 空）= 全员可见（向后兼容存量配置）；
     * 绑定了则要求与本人角色集合有交集。任一入口绑定角色才触发本人角色查询（懒 RPC）。
     */
    private List<ImWorkbenchItem> filterByRole(List<ImWorkbenchItem> items) {
        if (items.stream().noneMatch(ImWorkbenchService::hasRoleBinding)) {
            return items;
        }
        Set<Long> mine = new HashSet<>(nullableList(
                remoteUserService.getUserRoleIds(StpTenantUtil.getUserIdFromLoginId())));
        return items.stream().filter(item -> {
            if (!hasRoleBinding(item)) {
                return true;
            }
            for (Long id : parseRoleIds(item.getRoleIds())) {
                if (mine.contains(id)) {
                    return true;
                }
            }
            return false;
        }).toList();
    }

    private static boolean hasRoleBinding(ImWorkbenchItem item) {
        return item.getRoleIds() != null && !item.getRoleIds().isBlank();
    }

    /** 逗号分隔角色编号解析（忽略脏段） */
    private static Set<Long> parseRoleIds(String roleIds) {
        Set<Long> result = new HashSet<>();
        for (String part : roleIds.split(",")) {
            try {
                result.add(Long.valueOf(part.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据段跳过
            }
        }
        return result;
    }

    private static <T> List<T> nullableList(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 基址解析（委托 {@link ImWebBaseResolver}：租户台账行 web_base_url > 全局配置 > 内置缺省） */
    private String resolveWebBase() {
        return webBaseResolver.resolve(TenantContext.getRequiredTenantId());
    }

    /**
     * 覆盖式保存（全删全插，同租户唯一键 (tenant_id, path, deleted) 由物理删除保证可反复保存）。
     * 免登授权入口必须关联开放平台应用：存在 + 本租户 + 启用三重校验，行上落 open_app_id
     * 与冗余 app_key；直连入口清空两列。
     */
    @Transactional(rollbackFor = Exception.class)
    public void save(List<ItemSave> items) {
        Long tenantId = TenantContext.getRequiredTenantId();
        Map<Long, ImWorkbenchItem> oldById = new HashMap<>();
        for (ImWorkbenchItem old : itemMapper.selectList(new LambdaQueryWrapper<ImWorkbenchItem>()
                .eq(ImWorkbenchItem::getTenantId, tenantId))) {
            oldById.put(old.getId(), old);
            itemMapper.deletePhysicallyById(old.getId());
        }
        int index = 0;
        for (ItemSave item : items == null ? List.<ItemSave>of() : items) {
            ImWorkbenchItem old = item.id() == null ? null : oldById.get(item.id());
            ImWorkbenchItem row = new ImWorkbenchItem();
            row.setTenantId(tenantId);
            if (old != null) {
                row.setId(old.getId());
            }
            int entryType = item.entryType() == null ? ENTRY_TYPE_MENU : item.entryType();
            int authMode = entryType == ENTRY_TYPE_EXTERNAL && item.authMode() != null ? item.authMode() : AUTH_MODE_NONE;
            row.setEntryType(entryType);
            row.setPath(validatePath(entryType, item.path()));
            if (entryType == ENTRY_TYPE_EXTERNAL && (item.title() == null || item.title().isBlank())) {
                throw new ServiceException(400, "外部应用入口必须填写名称");
            }
            row.setTitle(blankToNull(item.title()));
            row.setIcon(blankToNull(item.icon()));
            row.setGroupName(blankToNull(item.groupName()));
            row.setOpenMode(item.openMode() == null ? 0 : item.openMode());
            row.setAuthMode(authMode);
            if (authMode == AUTH_MODE_OAUTH) {
                OpenAppBrief app = requireBoundApp(item.openAppId(), tenantId);
                row.setOpenAppId(app.getId());
                row.setAppKey(app.getAppKey());
            } else {
                row.setOpenAppId(null);
                row.setAppKey(null);
            }
            row.setRoleIds(validateRoleIds(item.roleIds(), tenantId));
            row.setSort(item.sort() == null ? index : item.sort());
            row.setStatus(item.status() != null && item.status() == 1 ? 1 : 0);
            itemMapper.insert(row);
            index++;
        }
    }

    /** 免登授权关联应用三重校验：存在 + 本租户 + 启用（防跨租户硬塞/停用应用继续绑定） */
    private OpenAppBrief requireBoundApp(Long openAppId, Long tenantId) {
        if (openAppId == null) {
            throw new ServiceException(400, "免登授权入口必须选择关联的开放平台应用");
        }
        OpenAppBrief app = gateway().getApp(openAppId);
        if (app == null) {
            throw new ServiceException(400, "关联的开放平台应用不存在");
        }
        if (!tenantId.equals(app.getTenantId())) {
            throw new ServiceException(400, "关联的开放平台应用不属于本租户");
        }
        if (app.getStatus() != null && app.getStatus() != 0) {
            throw new ServiceException(400, "关联的开放平台应用已停用");
        }
        return app;
    }

    /** 契约网关（开放平台未接入时免登授权整体不可用，直连入口不受影响） */
    private OpenPlatformGateway gateway() {
        OpenPlatformGateway gateway = openAppGateway.getIfAvailable();
        if (gateway == null) {
            throw new ServiceException(400, "开放平台能力未接入（未部署 mse-open），免登授权入口不可用");
        }
        return gateway;
    }

    /**
     * 角色绑定校验：空 = 全员可见（落 NULL，向后兼容语义）；非空逐个校验属本租户启用角色，
     * 去重后逗号 join 落列。入口类型不限（菜单入口本有菜单集过滤，此列叠加角色门控）。
     */
    private String validateRoleIds(List<Long> roleIds, Long tenantId) {
        if (roleIds == null || roleIds.isEmpty()) {
            return null;
        }
        List<RoleDTO> tenantRoles = nullableList(remoteRoleService.listRoles());
        Set<Long> validIds = new HashSet<>();
        for (RoleDTO role : tenantRoles) {
            if (role.getId() != null && (role.getStatus() == null || role.getStatus() == 0)) {
                validIds.add(role.getId());
            }
        }
        Set<Long> picked = new LinkedHashSet<>();
        for (Long id : roleIds) {
            if (id == null) {
                continue;
            }
            if (!validIds.contains(id)) {
                throw new ServiceException(400, "可见角色不存在、已停用或不属于本租户：" + id);
            }
            picked.add(id);
        }
        if (picked.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : picked) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        String joined = sb.toString();
        if (joined.length() > 500) {
            throw new ServiceException(400, "可见角色数量过多（上限 500 字符）");
        }
        return joined;
    }

    /** 本租户启用角色清单（配置页可见角色下拉候选） */
    public List<RoleBrief> listRoles() {
        List<RoleDTO> roles = nullableList(remoteRoleService.listRoles());
        return roles.stream()
                .filter(r -> r.getStatus() == null || r.getStatus() == 0)
                .map(r -> new RoleBrief(r.getId(), r.getName()))
                .toList();
    }

    private static String validatePath(int entryType, String path) {
        if (path == null || path.isBlank()) {
            throw new ServiceException(400, "工作台入口地址不能为空");
        }
        String trimmed = path.trim();
        if (entryType == ENTRY_TYPE_EXTERNAL) {
            if (!trimmed.matches("^https?://\\S+$")) {
                throw new ServiceException(400, "外部应用地址必须为 http(s) 绝对地址：" + trimmed);
            }
        } else if (!trimmed.startsWith("/")) {
            throw new ServiceException(400, "工作台入口 path 必须以 / 开头：" + trimmed);
        }
        return trimmed;
    }

    /**
     * 外部应用入口跳转：直连（auth_mode=0）原样返回 URL；
     * 免登授权（auth_mode=1）校验入口域名落在应用域名白名单内后签发一次性授权码并拼到
     * 回调地址（对方服务端再经 /im/oauth/token 兑换）。域名白名单是 code 授权模式的回调域
     * 安全约束（同微信公众号授权回调域语义），未配置 = 不限制。
     */
    public JumpVO jump(Long id) {
        Long tenantId = TenantContext.getRequiredTenantId();
        ImWorkbenchItem item = itemMapper.selectById(id);
        if (item == null || !tenantId.equals(item.getTenantId())) {
            throw new ServiceException(404, "工作台入口不存在");
        }
        if (item.getEntryType() == null || item.getEntryType() != ENTRY_TYPE_EXTERNAL) {
            throw new ServiceException(400, "该入口为 mse 页面菜单，请使用 SSO 跳转");
        }
        if (item.getStatus() != null && item.getStatus() != 0) {
            throw new ServiceException(400, "该工作台入口已停用");
        }
        // 角色可见性复核（防绕过客户端直调 jump；未绑定角色=全员可见，与 view() 同口径）
        if (hasRoleBinding(item)) {
            Set<Long> mine = new HashSet<>(nullableList(
                    remoteUserService.getUserRoleIds(StpTenantUtil.getUserIdFromLoginId())));
            boolean allowed = false;
            for (Long roleId : parseRoleIds(item.getRoleIds())) {
                if (mine.contains(roleId)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new ServiceException(403, "无权访问该工作台入口（未授权角色）");
            }
        }
        if (item.getAuthMode() == null || item.getAuthMode() != AUTH_MODE_OAUTH) {
            return new JumpVO(item.getPath(), 0);
        }
        // 回调域安全：入口 URL 的 host 必须被应用域名白名单放行（fail-closed：应用不存在/解析失败一律拒绝）
        if (!gateway().domainAllowed(item.getAppKey(), uriHost(item.getPath()))) {
            throw new ServiceException(403, "入口域名不在应用「域名白名单」内，请在开放平台「应用管理」为该应用配置域名白名单");
        }
        rateLimit(tenantId, StpTenantUtil.getUserIdFromLoginId());
        String code = WbSec.newCode();
        AuthCodePayload payload = new AuthCodePayload(tenantId, StpTenantUtil.getUserIdFromLoginId(),
                StpTenantUtil.getUsername(), nickname(), item.getId(), item.getAppKey());
        int ttl = properties.getWorkbench().getAuthCodeTtlSeconds();
        redis.opsForValue().set(AUTH_CODE_KEY_PREFIX + code, WbSec.JSON.writeValueAsString(payload),
                Duration.ofSeconds(ttl));
        return new JumpVO(WbSec.appendParam(item.getPath(), "code=" + code), ttl);
    }

    /** 入口 URL 的 host（域名白名单比对用，不含端口；解析失败返回 null → fail-closed） */
    private static String uriHost(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 同用户跳转签发限频：60 秒窗口内超过上限即拒绝（Redis INCR + 首次过期） */
    private void rateLimit(Long tenantId, Long userId) {
        String rateLimitKey = RATE_LIMIT_KEY_PREFIX + tenantId + "-" + userId;
        Long count = redis.opsForValue().increment(rateLimitKey);
        if (count != null && count == 1) {
            redis.expire(rateLimitKey, Duration.ofSeconds(RATE_LIMIT_WINDOW_SECONDS));
        }
        if (count != null && count > properties.getWorkbench().getJumpRateLimit()) {
            throw new ServiceException(429, "跳转链接签发过于频繁，请稍后再试");
        }
    }

    /** 本租户开放平台应用清单（配置页免登入口下拉候选；开放平台未接入返回空清单） */
    public List<OpenAppBrief> listOpenApps() {
        OpenPlatformGateway gateway = openAppGateway.getIfAvailable();
        return gateway == null ? List.of() : gateway.listApps(TenantContext.getRequiredTenantId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nickname() {
        Object nickname = StpTenantUtil.getSession().get(StpTenantUtil.SESSION_NICKNAME);
        return nickname == null ? null : nickname.toString();
    }

    /** 工作台视图（webBaseUrl 为免登跳转的 mse Web 端基址；items 含 entry_type/open_mode/auth_mode/role_ids/status） */
    public record WorkbenchVO(String webBaseUrl, List<ImWorkbenchItem> items) {
    }

    /** 保存项（entryType=0 path 以 / 开头；entryType=1 path 为 http(s) URL 且 title 必填、
     *  authMode=1 时 openAppId 必填；roleIds 空=全员可见，非空逐个校验属本租户启用角色；
     *  groupName 空=客户端按菜单树目录推导分组） */
    public record ItemSave(Long id, Integer entryType, String path, String title, String icon, String groupName,
                           Integer openMode, Integer authMode, Long openAppId, List<Long> roleIds,
                           Integer sort, Integer status) {
    }

    /** 跳转结果（url 已追加 code 参数；expiresIn 为授权码有效期秒数，0 = 直连无授权码） */
    public record JumpVO(String url, int expiresIn) {
    }

    /** 角色下拉候选（配置页可见角色选择用） */
    public record RoleBrief(Long id, String name) {
    }

    /** 授权码绑定的签发者与应用（Jackson record，存 Redis） */
    record AuthCodePayload(Long tenantId, Long userId, String username, String nickname,
                           Long itemId, String appKey) {
    }
}
