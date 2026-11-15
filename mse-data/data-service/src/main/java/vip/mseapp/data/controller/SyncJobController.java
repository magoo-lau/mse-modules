package vip.mseapp.data.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.scheduling.support.CronExpression;
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
import vip.mseapp.data.entity.DatSyncJob;
import vip.mseapp.data.entity.DatSyncJobLog;
import vip.mseapp.data.service.LineageService;
import vip.mseapp.data.service.SyncJobService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据集成同步任务（租户端）。
 */
@Validated
@RestController
@RequestMapping("/data/sync")
public class SyncJobController {

    private final SyncJobService syncJobService;
    private final LineageService lineageService;

    public SyncJobController(SyncJobService syncJobService, LineageService lineageService) {
        this.syncJobService = syncJobService;
        this.lineageService = lineageService;
    }

    @SaCheckPermission(value = "data:sync:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/page")
    public Result<PageResult<DatSyncJob>> page(@RequestParam(defaultValue = "1") long pageNum,
                                               @RequestParam(defaultValue = "10") long pageSize,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Integer status) {
        return Result.ok(syncJobService.page(pageNum, pageSize, keyword, status));
    }

    @SaCheckPermission(value = "data:sync:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/{id}")
    public Result<DatSyncJob> get(@PathVariable Long id) {
        return Result.ok(syncJobService.get(id));
    }

    @SaCheckPermission(value = "data:sync:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<Long> create(@RequestBody @Validated SaveRequest request) {
        return Result.ok(syncJobService.create(toEntity(request)));
    }

    @SaCheckPermission(value = "data:sync:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping
    public Result<Void> update(@RequestBody @Validated UpdateRequest request) {
        DatSyncJob job = toEntity(new SaveRequest(request.name(), request.datasourceId(),
                request.sourceType(), request.sourceTable(), request.querySql(), request.targetTable(),
                request.syncMode(), request.incrColumn(), request.cron(), request.depJobId(), request.remark()));
        job.setId(request.id());
        syncJobService.update(job);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:sync:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enable) {
        syncJobService.changeStatus(id, enable);
        return Result.ok();
    }

    /** 立即执行（异步），前端轮询 /logs 观察结果 */
    @SaCheckPermission(value = "data:sync:run", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/{id}/run")
    public Result<Void> run(@PathVariable Long id) {
        syncJobService.run(id);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:sync:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/logs")
    public Result<PageResult<DatSyncJobLog>> logs(@RequestParam(defaultValue = "1") long pageNum,
                                                  @RequestParam(defaultValue = "10") long pageSize,
                                                  @RequestParam(required = false) Long jobId) {
        return Result.ok(syncJobService.logs(pageNum, pageSize, jobId));
    }

    /** 按表名查血缘（上下游） */
    @SaCheckPermission(value = "data:sync:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/lineage")
    public Result<LineageService.LineageView> lineage(@RequestParam String tableName) {
        return Result.ok(lineageService.byTable(tableName));
    }

    /**
     * cron 解析与最近 N 次执行时间预览：与调度器同用 Spring CronExpression，非法时返回 valid=false 走结构化结果
     * （前端内联提示，不抛 400）。同步任务与质量规则两个对话框共用，任一 list 权限即可。
     */
    @SaCheckPermission(value = {"data:sync:list", "data:quality:list"}, type = MseConstants.LOGIN_TYPE_TENANT, mode = SaMode.OR)
    @GetMapping("/cron-preview")
    public Result<CronPreview> cronPreview(@RequestParam String cron) {
        String expression = cron == null ? "" : cron.trim();
        if (expression.isEmpty()) {
            return Result.ok(new CronPreview(false, "cron 表达式为空", List.of()));
        }
        CronExpression parsed;
        try {
            parsed = CronExpression.parse(expression);
        } catch (IllegalArgumentException e) {
            return Result.ok(new CronPreview(false, "表达式非法: " + e.getMessage(), List.of()));
        }
        List<String> times = new ArrayList<>(10);
        LocalDateTime time = LocalDateTime.now().withNano(0);
        while (times.size() < 10) {
            time = parsed.next(time);
            if (time == null) {
                break;
            }
            times.add(time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }
        return Result.ok(new CronPreview(true, null, times));
    }

    public record CronPreview(boolean valid, String message, List<String> nextTimes) {
    }

    @SaCheckPermission(value = "data:sync:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        syncJobService.delete(id);
        return Result.ok();
    }

    private DatSyncJob toEntity(SaveRequest request) {
        DatSyncJob job = new DatSyncJob();
        job.setName(request.name());
        job.setDatasourceId(request.datasourceId());
        job.setSourceType(request.sourceType());
        job.setSourceTable(request.sourceTable());
        job.setQuerySql(request.querySql());
        job.setTargetTable(request.targetTable());
        job.setSyncMode(request.syncMode());
        job.setIncrColumn(request.incrColumn());
        job.setCron(request.cron());
        job.setDepJobId(request.depJobId());
        job.setRemark(request.remark());
        return job;
    }

    public record SaveRequest(
            @NotBlank(message = "任务名称不能为空") String name,
            @NotNull(message = "数据源不能为空") Long datasourceId,
            @NotBlank(message = "来源类型不能为空") String sourceType,
            String sourceTable,
            String querySql,
            @NotBlank(message = "目标表不能为空") String targetTable,
            @NotBlank(message = "同步模式不能为空") String syncMode,
            String incrColumn,
            String cron,
            /** 0 = 无前置任务 */
            Long depJobId,
            String remark) {
    }

    public record UpdateRequest(
            @NotNull(message = "编号不能为空") Long id,
            @NotBlank(message = "任务名称不能为空") String name,
            @NotNull(message = "数据源不能为空") Long datasourceId,
            @NotBlank(message = "来源类型不能为空") String sourceType,
            String sourceTable,
            String querySql,
            @NotBlank(message = "目标表不能为空") String targetTable,
            @NotBlank(message = "同步模式不能为空") String syncMode,
            String incrColumn,
            String cron,
            /** 0 = 无前置任务 */
            Long depJobId,
            String remark) {
    }
}
