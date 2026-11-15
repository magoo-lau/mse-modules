package vip.mseapp.open.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.open.entity.OpenUserBind;
import vip.mseapp.open.mapper.OpenUserBindMapper;

import java.util.List;

/**
 * 统一身份绑定：外部系统用户标识 ↔ 中台账号。
 *
 * <p>用户存在性校验经基座契约（RemoteUserService）调用 mse-system；绑定关系本表在本服务库。
 * <p>租户隔离语义：开放链路（/open/api/**）由鉴权拦截器注入应用绑定租户，
 * 增删查均限定在租户域内；管理链路（/openapp/bind）由管理服务显式 runIgnore 跨租户检索。
 */
@Service
public class OpenBindService {

    private static final Logger log = LoggerFactory.getLogger(OpenBindService.class);

    /** 用户选项上限：管理端下拉够用，超限租户属异常规模 */
    private static final int USER_OPTION_LIMIT = 500;

    private final OpenUserBindMapper bindMapper;

    @RpcReference
    private RemoteUserService userService;

    public OpenBindService(OpenUserBindMapper bindMapper) {
        this.bindMapper = bindMapper;
    }

    /** 绑定结果（bindId 供后续解绑） */
    public record BindCreated(Long bindId, Long userId, String sourceSystem, String externalUserId) {
    }

    /** 解析结果：外部标识 → 中台账号 */
    public record BindResolve(OpenUserBind bind, UserDTO user) {
    }

    /** 用户下拉选项（绑定检索页筛选用） */
    public record UserOption(Long id, String username, String nickname, Integer status) {
    }

    @Transactional(rollbackFor = Exception.class)
    public BindCreated bind(Long userId, String sourceSystem, String externalUserId, String remark) {
        String source = requireText(sourceSystem, "sourceSystem 不能为空");
        String external = requireText(externalUserId, "externalUserId 不能为空");
        if (userService.getUserById(userId) == null) {
            throw new ServiceException(404, "用户不存在: " + userId);
        }
        Long duplicated = bindMapper.selectCount(new LambdaQueryWrapper<OpenUserBind>()
                .eq(OpenUserBind::getSourceSystem, source)
                .eq(OpenUserBind::getExternalUserId, external));
        if (duplicated != null && duplicated > 0) {
            throw new ServiceException(400, "该外部标识已绑定（" + source + ":" + external + "）");
        }
        OpenUserBind bind = new OpenUserBind();
        bind.setUserId(userId);
        bind.setSourceSystem(source);
        bind.setExternalUserId(external);
        bind.setRemark(remark);
        bindMapper.insert(bind);
        return new BindCreated(bind.getId(), userId, source, external);
    }

    /** 按外部标识解析中台账号；未绑定返回 null */
    public BindResolve resolve(String sourceSystem, String externalUserId) {
        String source = requireText(sourceSystem, "sourceSystem 不能为空");
        String external = requireText(externalUserId, "externalUserId 不能为空");
        OpenUserBind bind = bindMapper.selectOne(new LambdaQueryWrapper<OpenUserBind>()
                .eq(OpenUserBind::getSourceSystem, source)
                .eq(OpenUserBind::getExternalUserId, external));
        if (bind == null) {
            return null;
        }
        UserDTO user = userService.getUserById(bind.getUserId());
        if (user == null) {
            // 脏绑定（用户已物理删除）：如实告知未找到
            return null;
        }
        return new BindResolve(bind, user);
    }

    /** 一个中台账号的全部外部身份 */
    public List<OpenUserBind> listByUser(Long userId) {
        return bindMapper.selectList(new LambdaQueryWrapper<OpenUserBind>()
                .eq(OpenUserBind::getUserId, userId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void unbind(Long bindId) {
        OpenUserBind bind = bindMapper.selectById(bindId);
        if (bind == null) {
            throw new ServiceException(404, "绑定关系不存在: " + bindId);
        }
        bindMapper.deleteById(bindId);
    }

    /** 用户下拉选项（绑定检索页；指定租户域内查询，平台域传 0）。契约异常降级为空列表并告警 */
    public List<UserOption> listUserOptions(long tenantId) {
        try {
            PageResult<UserDTO> page = TenantContext.runAs(tenantId,
                    () -> userService.pageUsers(1, USER_OPTION_LIMIT, null, null, null));
            return page.records().stream()
                    .map(u -> new UserOption(u.getId(), u.getUsername(), u.getNickname(), u.getStatus()))
                    .toList();
        } catch (Throwable e) {
            log.warn("[mse-open] 绑定用户选项获取失败（租户 {}），降级为空: {}", tenantId, e.getMessage());
            return List.of();
        }
    }

    /** 来源系统下拉选项（绑定表已有值的 distinct；tenantId 非空时仅该租户域——租户会话域锁定） */
    public List<String> listSourceOptions(Long tenantId) {
        return TenantContext.runIgnore(() -> bindMapper.selectObjs(new QueryWrapper<OpenUserBind>()
                        .select("DISTINCT source_system").isNotNull("source_system")
                        .eq(tenantId != null, "tenant_id", tenantId)
                        .orderByAsc("source_system"))
                .stream().map(String::valueOf).filter(s -> !s.isBlank()).toList());
    }

    /** 绑定分页检索（runIgnore 豁免隔离；tenantId 由控制器按会话世界解析——租户会话锁定本租户） */
    public PageResult<OpenUserBind> pageAll(long pageNum, long pageSize, Long tenantId,
                                            Long userId, String sourceSystem) {
        return TenantContext.runIgnore(() -> {
            LambdaQueryWrapper<OpenUserBind> wrapper = new LambdaQueryWrapper<OpenUserBind>()
                    .eq(tenantId != null, OpenUserBind::getTenantId, tenantId)
                    .eq(userId != null, OpenUserBind::getUserId, userId)
                    .like(sourceSystem != null && !sourceSystem.isBlank(),
                            OpenUserBind::getSourceSystem, sourceSystem)
                    .orderByDesc(OpenUserBind::getId);
            Page<OpenUserBind> page = bindMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
            return PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords());
        });
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(400, message);
        }
        return value.trim();
    }
}
