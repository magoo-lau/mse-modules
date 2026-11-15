package vip.mseapp.open.service;

import org.springframework.stereotype.Service;
import vip.mseapp.api.system.RemoteDeptService;
import vip.mseapp.api.system.RemoteRoleService;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.DeptTreeNodeDTO;
import vip.mseapp.api.system.dto.RoleDTO;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.security.crypto.MsePasswords;

import java.util.List;

/**
 * 用户能力开放：经基座契约（{@link RemoteUserService}/{@link RemoteDeptService}/{@link RemoteRoleService}）
 * 供机器凭证调用——单体 local 模式本地直调，微服务 dubbo 模式远程调用 mse-system，本类零感知。
 *
 * <p>数据隔离由基座租户插件完成（拦截器注入应用绑定租户，调用方上下文经 RPC 透传）；
 * 机器凭证链路无用户上下文，数据范围解析返回 null（不过滤），行为与平台内部系统调用一致。
 * 开放链路禁止创建/修改超级管理员标记（契约实现侧强制普通用户）。
 *
 * <p>方法内为多次契约编排（创建 + 角色分配等），非同一本地事务：极端情况（第二步失败）
 * 由接入方按返回错误重试/补偿，与脚手架"Seata 默认关闭"的事务边界一致。
 */
@Service
public class OpenUserSyncService {

    @RpcReference
    private RemoteUserService userService;

    @RpcReference
    private RemoteDeptService deptService;

    @RpcReference
    private RemoteRoleService roleService;

    // -------------------- 用户 --------------------

    public UserDTO getById(Long id) {
        UserDTO user = userService.getUserById(id);
        if (user == null) {
            throw new ServiceException(404, "用户不存在: " + id);
        }
        return user;
    }

    public UserDTO getByUsername(String username) {
        UserDTO user = userService.getUserByUsername(TenantContext.getRequiredTenantId(), username);
        if (user == null) {
            throw new ServiceException(404, "用户不存在: " + username);
        }
        return user;
    }

    public vip.mseapp.core.result.PageResult<UserDTO> page(long pageNum, long pageSize,
                                                           String username, Integer status, Long deptId) {
        return userService.pageUsers(pageNum, pageSize, username, status, deptId);
    }

    public Long create(String username, String password, String nickname, String mobile,
                       String email, Long deptId, List<Long> roleIds) {
        UserDTO user = new UserDTO();
        user.setUsername(username);
        user.setPassword(MsePasswords.encode(password));
        user.setNickname(nickname);
        user.setMobile(mobile);
        user.setEmail(email);
        user.setDeptId(deptId);
        user.setStatus(0);
        UserDTO created = userService.createUser(user);
        if (roleIds != null && !roleIds.isEmpty()) {
            userService.assignRoles(created.getId(), roleIds);
        }
        return created.getId();
    }

    public void update(Long id, String nickname, String mobile, String email, Integer status, Long deptId) {
        getById(id);
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setNickname(nickname);
        user.setMobile(mobile);
        user.setEmail(email);
        user.setStatus(status);
        user.setDeptId(deptId);
        userService.updateUser(user);
    }

    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new ServiceException(400, "status 取值仅支持 0（启用）/ 1（停用）");
        }
        getById(id);
        UserDTO user = new UserDTO();
        user.setId(id);
        user.setStatus(status);
        userService.updateUser(user);
    }

    public void resetPassword(Long id, String password) {
        getById(id);
        userService.resetPassword(id, MsePasswords.encode(password));
    }

    public void delete(Long id) {
        getById(id);
        userService.deleteUser(id);
    }

    public List<Long> getRoleIds(Long id) {
        getById(id);
        return userService.getUserRoleIds(id);
    }

    /** 覆盖式分配角色（传空数组 = 清空角色） */
    public void assignRoles(Long id, List<Long> roleIds) {
        getById(id);
        userService.assignRoles(id, roleIds == null ? List.of() : roleIds);
    }

    // -------------------- 部门 --------------------

    public List<DeptTreeNodeDTO> deptTree() {
        return deptService.getDeptTree();
    }

    public Long createDept(Long parentId, String name, Integer sort) {
        return deptService.createDept(parentId, name, sort);
    }

    public void updateDept(Long id, Long parentId, String name, Integer sort, Integer status) {
        deptService.updateDept(id, parentId, name, sort, status);
    }

    public void deleteDept(Long id) {
        deptService.deleteDept(id);
    }

    // -------------------- 角色 --------------------

    public List<RoleDTO> listRoles() {
        return roleService.listRoles();
    }

    public Long createRole(String name, String code, Integer sort, Integer dataScope) {
        if (dataScope != null && (dataScope < 1 || dataScope > 5)) {
            throw new ServiceException(400, "dataScope 取值须为 1-5");
        }
        RoleDTO role = new RoleDTO();
        role.setName(name);
        role.setCode(code);
        role.setSort(sort);
        role.setDataScope(dataScope);
        return roleService.createRole(role);
    }
}
