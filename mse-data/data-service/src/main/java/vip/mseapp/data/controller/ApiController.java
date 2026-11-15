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
import vip.mseapp.data.entity.DatApi;
import vip.mseapp.data.entity.DatApiApproval;
import vip.mseapp.data.open.OpenApiService;
import vip.mseapp.data.service.ApiService;

import java.util.List;

/**
 * 数据服务 API 管理（租户端）。
 */
@Validated
@RestController
@RequestMapping("/data/api")
public class ApiController {

    private final ApiService apiService;

    public ApiController(ApiService apiService) {
        this.apiService = apiService;
    }

    @SaCheckPermission(value = "data:service:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<DatApi>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "10") long pageSize,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer status) {
        return Result.ok(apiService.page(pageNum, pageSize, keyword, status));
    }

    @SaCheckPermission(value = "data:service:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}")
    public Result<DatApi> get(@PathVariable Long id) {
        return Result.ok(apiService.get(id));
    }

    @SaCheckPermission(value = "data:service:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> create(@RequestBody @Validated SaveRequest request) {
        return Result.ok(apiService.create(toEntity(request)));
    }

    @SaCheckPermission(value = "data:service:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping
    public Result<Void> update(@RequestBody @Validated UpdateRequest request) {
        DatApi api = toEntity(new SaveRequest(request.name(), request.path(), request.datasourceId(),
                request.sqlTemplate(), request.paramsJson(), request.pageEnabled(), request.maxRows(),
                request.timeoutMs(), request.qpsLimit(), request.remark()));
        api.setId(request.id());
        apiService.update(api);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:service:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        apiService.submit(id);
        return Result.ok();
    }

    /** 审批：通过/驳回（需审批权限） */
    @SaCheckPermission(value = "data:service:approve", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id, @RequestBody(required = false) ApproveRequest request) {
        boolean pass = request == null || Boolean.TRUE.equals(request.pass());
        apiService.approve(id, pass, request == null ? null : request.comment());
        return Result.ok();
    }

    @SaCheckPermission(value = "data:service:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/offline")
    public Result<Void> offline(@PathVariable Long id,
                                @RequestBody(required = false) ApproveRequest request) {
        apiService.offline(id, request == null ? null : request.comment());
        return Result.ok();
    }

    /** 审批记录 */
    @SaCheckPermission(value = "data:service:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}/approvals")
    public Result<List<DatApiApproval>> approvals(@PathVariable Long id) {
        return Result.ok(apiService.approvals(id));
    }

    /** 试运行：仅取 10 行预览（跳过授权/限流） */
    @SaCheckPermission(value = "data:service:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/preview")
    public Result<OpenApiService.OpenResponse> preview(@PathVariable Long id,
                                                       @RequestBody(required = false) PreviewRequest request) {
        String paramsJson = request == null ? null : request.paramsJson();
        return Result.ok(apiService.preview(id, paramsJson));
    }

    @SaCheckPermission(value = "data:service:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        apiService.delete(id);
        return Result.ok();
    }

    private DatApi toEntity(SaveRequest request) {
        DatApi api = new DatApi();
        api.setName(request.name());
        api.setPath(request.path());
        api.setDatasourceId(request.datasourceId());
        api.setSqlTemplate(request.sqlTemplate());
        api.setParamsJson(request.paramsJson());
        api.setPageEnabled(request.pageEnabled());
        api.setMaxRows(request.maxRows());
        api.setTimeoutMs(request.timeoutMs());
        api.setQpsLimit(request.qpsLimit());
        api.setRemark(request.remark());
        return api;
    }

    public record SaveRequest(
            @NotBlank(message = "名称不能为空") String name,
            @NotBlank(message = "路径不能为空") String path,
            Long datasourceId,
            @NotBlank(message = "SQL 不能为空") String sqlTemplate,
            String paramsJson,
            Integer pageEnabled,
            Integer maxRows,
            Integer timeoutMs,
            Integer qpsLimit,
            String remark) {
    }

    public record UpdateRequest(
            @NotNull(message = "编号不能为空") Long id,
            @NotBlank(message = "名称不能为空") String name,
            @NotBlank(message = "路径不能为空") String path,
            Long datasourceId,
            @NotBlank(message = "SQL 不能为空") String sqlTemplate,
            String paramsJson,
            Integer pageEnabled,
            Integer maxRows,
            Integer timeoutMs,
            Integer qpsLimit,
            String remark) {
    }

    public record PreviewRequest(String paramsJson) {
    }

    public record ApproveRequest(Boolean pass, String comment) {
    }
}
