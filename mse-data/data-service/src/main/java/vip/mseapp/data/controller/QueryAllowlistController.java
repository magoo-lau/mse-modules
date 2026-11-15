package vip.mseapp.data.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.data.entity.DatQueryAllowlist;
import vip.mseapp.data.service.QueryAllowlistService;

/**
 * 中台库通道查询白名单（租户端）：维护允许经 datasource_id=0 查询的中台库业务表。
 * 独立库租户的白名单业务表在执行期路由至其租户专属库。
 */
@Validated
@RestController
@RequestMapping("/data/allow")
public class QueryAllowlistController {

    private final QueryAllowlistService allowlistService;

    public QueryAllowlistController(QueryAllowlistService allowlistService) {
        this.allowlistService = allowlistService;
    }

    @SaCheckPermission(value = "data:allow:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<DatQueryAllowlist>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                      @RequestParam(defaultValue = "10") long pageSize,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Integer status) {
        return Result.ok(allowlistService.page(pageNum, pageSize, keyword, status));
    }

    @SaCheckPermission(value = "data:allow:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> create(@RequestBody @Validated CreateRequest request) {
        DatQueryAllowlist row = new DatQueryAllowlist();
        row.setTableName(request.tableName());
        row.setRemark(request.remark());
        return Result.ok(allowlistService.create(row));
    }

    @SaCheckPermission(value = "data:allow:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping
    public Result<Void> update(@RequestBody @Validated UpdateRequest request) {
        DatQueryAllowlist row = new DatQueryAllowlist();
        row.setId(request.id());
        row.setTableName(request.tableName());
        row.setRemark(request.remark());
        allowlistService.update(row);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:allow:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enable) {
        allowlistService.changeStatus(id, enable);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:allow:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        allowlistService.delete(id);
        return Result.ok();
    }

    public record CreateRequest(
            @NotBlank(message = "表名不能为空") String tableName,
            String remark) {
    }

    public record UpdateRequest(
            @NotNull(message = "编号不能为空") Long id,
            @NotBlank(message = "表名不能为空") String tableName,
            String remark) {
    }
}
