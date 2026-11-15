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
import vip.mseapp.data.entity.DatDatasource;
import vip.mseapp.data.service.DatasourceService;

/**
 * 数据源管理（租户端）：外部业务库的注册与连通性测试。
 */
@Validated
@RestController
@RequestMapping("/data/source")
public class DatasourceController {

    private final DatasourceService datasourceService;

    public DatasourceController(DatasourceService datasourceService) {
        this.datasourceService = datasourceService;
    }

    @SaCheckPermission(value = "data:source:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<DatDatasource>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) String keyword,
                                                  @RequestParam(required = false) Integer status) {
        return Result.ok(datasourceService.page(pageNum, pageSize, keyword, status));
    }

    @SaCheckPermission(value = "data:source:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}")
    public Result<DatDatasource> get(@PathVariable Long id) {
        return Result.ok(datasourceService.get(id));
    }

    @SaCheckPermission(value = "data:source:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> create(@RequestBody @Validated CreateRequest request) {
        DatDatasource ds = new DatDatasource();
        ds.setName(request.name());
        ds.setType(request.type());
        ds.setHost(request.host());
        ds.setPort(request.port());
        ds.setDbName(request.dbName());
        ds.setExtraParams(request.extraParams());
        ds.setUsername(request.username());
        ds.setPassword(request.password());
        ds.setRemark(request.remark());
        return Result.ok(datasourceService.create(ds));
    }

    @SaCheckPermission(value = "data:source:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping
    public Result<Void> update(@RequestBody @Validated UpdateRequest request) {
        DatDatasource ds = new DatDatasource();
        ds.setId(request.id());
        ds.setName(request.name());
        ds.setType(request.type());
        ds.setHost(request.host());
        ds.setPort(request.port());
        ds.setDbName(request.dbName());
        ds.setExtraParams(request.extraParams());
        ds.setUsername(request.username());
        ds.setPassword(request.password());
        ds.setRemark(request.remark());
        datasourceService.update(ds);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:source:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enable) {
        datasourceService.changeStatus(id, enable);
        return Result.ok();
    }

    /** 连通性测试：data.ok=false 时 message 带错误详情 */
    @SaCheckPermission(value = "data:source:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/test")
    public Result<DatasourceService.TestResult> test(@PathVariable Long id) {
        return Result.ok(datasourceService.test(id));
    }

    @SaCheckPermission(value = "data:source:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        datasourceService.delete(id);
        return Result.ok();
    }

    public record CreateRequest(
            @NotBlank(message = "名称不能为空") String name,
            @NotBlank(message = "类型不能为空") String type,
            @NotBlank(message = "主机不能为空") String host,
            @NotNull(message = "端口不能为空") Integer port,
            @NotBlank(message = "库名不能为空") String dbName,
            String extraParams,
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "口令不能为空") String password,
            String remark) {
    }

    public record UpdateRequest(
            @NotNull(message = "编号不能为空") Long id,
            @NotBlank(message = "名称不能为空") String name,
            @NotBlank(message = "类型不能为空") String type,
            @NotBlank(message = "主机不能为空") String host,
            @NotNull(message = "端口不能为空") Integer port,
            @NotBlank(message = "库名不能为空") String dbName,
            String extraParams,
            @NotBlank(message = "用户名不能为空") String username,
            /** 留空 = 保持不变 */
            String password,
            String remark) {
    }
}
