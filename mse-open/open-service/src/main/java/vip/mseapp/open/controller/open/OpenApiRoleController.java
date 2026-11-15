package vip.mseapp.open.controller.open;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.api.system.dto.RoleDTO;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.service.OpenUserSyncService;

import java.util.List;

/**
 * 角色能力开放 API（/open/api/role/**，应用 access_token 鉴权，租户域 = 应用绑定租户）。
 *
 * <p>开放链路只提供角色查询与基础创建（不挂菜单/权限点授权），
 * 授权矩阵仍由管理员在管理端维护，防止机器凭证越权改权限。
 */
@Validated
@RestController
@RequestMapping("/open/api/role")
public class OpenApiRoleController {

    private final OpenUserSyncService syncService;

    public OpenApiRoleController(OpenUserSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/list")
    public Result<List<RoleDTO>> list() {
        return Result.ok(syncService.listRoles());
    }

    @PostMapping
    public Result<Long> create(@RequestBody @Validated RoleCreateRequest request) {
        return Result.ok(syncService.createRole(request.name(), request.code(),
                request.sort(), request.dataScope()));
    }

    public record RoleCreateRequest(
            @NotBlank(message = "角色名称不能为空") String name,
            @NotBlank(message = "角色编码不能为空") String code,
            Integer sort,
            /** 数据范围 1 全部 2 自定义 3 本部门 4 本部门及以下 5 仅本人（默认 5） */
            Integer dataScope) {
    }
}
