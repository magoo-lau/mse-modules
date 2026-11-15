package vip.mseapp.open.controller.open;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.service.OpenExtendService;
import vip.mseapp.open.service.OpenUserSyncService;

import java.util.List;
import java.util.Map;

/**
 * 用户能力开放 API（/open/api/user/**，应用 access_token 鉴权，租户域 = 应用绑定租户）。
 *
 * <p>覆盖全量用户纳管生命周期：查询/创建/更新/启停/重置密码/删除/角色分配 + 扩展属性。
 * 每个端点对应 open_api 清单中的一个授权项（见 sql 种子数据）。
 * 数据经基座契约（RemoteUserService）调用 mse-system，本服务不直连 sys_user 表。
 */
@Validated
@RestController
@RequestMapping("/open/api/user")
public class OpenApiUserController {

    private final OpenUserSyncService syncService;
    private final OpenExtendService extendService;

    public OpenApiUserController(OpenUserSyncService syncService, OpenExtendService extendService) {
        this.syncService = syncService;
        this.extendService = extendService;
    }

    @GetMapping("/page")
    public Result<PageResult<UserDTO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) String username,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) Long deptId) {
        return Result.ok(syncService.page(pageNum, pageSize, username, status, deptId));
    }

    /** 按用户名精确查询（注册/登录场景） */
    @GetMapping("/by-username")
    public Result<UserDTO> byUsername(@RequestParam String username) {
        return Result.ok(syncService.getByUsername(username));
    }

    @GetMapping("/{id}")
    public Result<UserDTO> get(@PathVariable Long id) {
        return Result.ok(syncService.getById(id));
    }

    @PostMapping
    public Result<Long> create(@RequestBody @Validated UserCreateRequest request) {
        return Result.ok(syncService.create(request.username(), request.password(), request.nickname(),
                request.mobile(), request.email(), request.deptId(), request.roleIds()));
    }

    @PutMapping
    public Result<Void> update(@RequestBody @Validated UserUpdateRequest request) {
        syncService.update(request.id(), request.nickname(), request.mobile(),
                request.email(), request.status(), request.deptId());
        return Result.ok();
    }

    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestBody @Validated StatusRequest request) {
        syncService.changeStatus(id, request.status());
        return Result.ok();
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody @Validated PasswordRequest request) {
        syncService.resetPassword(id, request.password());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        syncService.delete(id);
        return Result.ok();
    }

    @GetMapping("/{id}/role-ids")
    public Result<List<Long>> roleIds(@PathVariable Long id) {
        return Result.ok(syncService.getRoleIds(id));
    }

    /** 覆盖式分配角色（传空数组 = 清空角色） */
    @PutMapping("/{id}/role-ids")
    public Result<Void> assignRoles(@PathVariable Long id, @RequestBody RoleIdsRequest request) {
        syncService.assignRoles(id, request.roleIds());
        return Result.ok();
    }

    // -------------------- 扩展属性 --------------------

    @GetMapping("/{id}/extend")
    public Result<Map<String, Object>> getExtend(@PathVariable Long id) {
        return Result.ok(extendService.get(id));
    }

    /** 全量覆盖写入扩展属性（JSON 对象，≤4KB） */
    @PutMapping("/{id}/extend")
    public Result<Void> putExtend(@PathVariable Long id,
                                  @RequestBody Map<String, Object> attributes) {
        extendService.put(id, attributes);
        return Result.ok();
    }

    // -------------------- 请求体 --------------------

    public record UserCreateRequest(
            @NotBlank(message = "用户名不能为空") @Size(max = 30) String username,
            @NotBlank(message = "密码不能为空") @Size(min = 6, max = 32) String password,
            String nickname,
            String mobile,
            String email,
            Long deptId,
            List<Long> roleIds) {
    }

    public record UserUpdateRequest(
            @NotNull(message = "用户编号不能为空") Long id,
            String nickname,
            String mobile,
            String email,
            Integer status,
            Long deptId) {
    }

    public record StatusRequest(@NotNull(message = "status 不能为空") Integer status) {
    }

    public record PasswordRequest(
            @NotBlank @Size(min = 6, max = 32, message = "密码长度须为 6-32 位") String password) {
    }

    public record RoleIdsRequest(List<Long> roleIds) {
    }
}
