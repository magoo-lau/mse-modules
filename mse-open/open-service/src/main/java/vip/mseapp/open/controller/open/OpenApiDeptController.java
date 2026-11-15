package vip.mseapp.open.controller.open;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.api.system.dto.DeptTreeNodeDTO;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.service.OpenUserSyncService;

import java.util.List;

/**
 * 部门能力开放 API（/open/api/dept/**，应用 access_token 鉴权，租户域 = 应用绑定租户）。
 */
@Validated
@RestController
@RequestMapping("/open/api/dept")
public class OpenApiDeptController {

    private final OpenUserSyncService syncService;

    public OpenApiDeptController(OpenUserSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/tree")
    public Result<List<DeptTreeNodeDTO>> tree() {
        return Result.ok(syncService.deptTree());
    }

    @PostMapping
    public Result<Long> create(@RequestBody @Validated DeptCreateRequest request) {
        return Result.ok(syncService.createDept(request.parentId(), request.name(), request.sort()));
    }

    @PutMapping
    public Result<Void> update(@RequestBody @Validated DeptUpdateRequest request) {
        syncService.updateDept(request.id(), request.parentId(), request.name(),
                request.sort(), request.status());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        syncService.deleteDept(id);
        return Result.ok();
    }

    public record DeptCreateRequest(
            Long parentId,
            @NotBlank(message = "部门名称不能为空") String name,
            Integer sort) {
    }

    public record DeptUpdateRequest(
            @NotNull(message = "部门编号不能为空") Long id,
            Long parentId,
            String name,
            Integer sort,
            Integer status) {
    }
}
