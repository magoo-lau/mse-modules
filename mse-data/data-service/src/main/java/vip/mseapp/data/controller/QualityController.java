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
import vip.mseapp.data.entity.DatQualityReport;
import vip.mseapp.data.entity.DatQualityRule;
import vip.mseapp.data.service.QualityService;

/**
 * 数据质量（租户端）：规则管理与报告查询。
 */
@Validated
@RestController
@RequestMapping("/data/quality")
public class QualityController {

    private final QualityService qualityService;

    public QualityController(QualityService qualityService) {
        this.qualityService = qualityService;
    }

    @SaCheckPermission(value = "data:quality:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/rule/page")
    public Result<PageResult<DatQualityRule>> page(@RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "10") long pageSize,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) Integer status) {
        return Result.ok(qualityService.page(pageNum, pageSize, keyword, status));
    }

    @SaCheckPermission(value = "data:quality:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/rule/{id}")
    public Result<DatQualityRule> get(@PathVariable Long id) {
        return Result.ok(qualityService.get(id));
    }

    @SaCheckPermission(value = "data:quality:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/rule")
    public Result<Long> create(@RequestBody @Validated SaveRequest request) {
        return Result.ok(qualityService.create(toEntity(request)));
    }

    @SaCheckPermission(value = "data:quality:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping("/rule")
    public Result<Void> update(@RequestBody @Validated UpdateRequest request) {
        DatQualityRule rule = toEntity(new SaveRequest(request.name(), request.datasourceId(),
                request.targetTable(), request.ruleType(), request.columnName(), request.ruleConfig(),
                request.cron(), request.remark()));
        rule.setId(request.id());
        qualityService.update(rule);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:quality:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/rule/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enable) {
        qualityService.changeStatus(id, enable);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:quality:run", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/rule/{id}/run")
    public Result<DatQualityReport> run(@PathVariable Long id) {
        return Result.ok(qualityService.run(id, "manual"));
    }

    @SaCheckPermission(value = "data:quality:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/rule/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        qualityService.delete(id);
        return Result.ok();
    }

    @SaCheckPermission(value = "data:quality:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/report/page")
    public Result<PageResult<DatQualityReport>> reports(@RequestParam(defaultValue = "1") long pageNum,
                                                        @RequestParam(defaultValue = "10") long pageSize,
                                                        @RequestParam(required = false) Long ruleId,
                                                        @RequestParam(required = false) String status) {
        return Result.ok(qualityService.reports(pageNum, pageSize, ruleId, status));
    }

    private DatQualityRule toEntity(SaveRequest request) {
        DatQualityRule rule = new DatQualityRule();
        rule.setName(request.name());
        rule.setDatasourceId(request.datasourceId());
        rule.setTargetTable(request.targetTable());
        rule.setRuleType(request.ruleType());
        rule.setColumnName(request.columnName());
        rule.setRuleConfig(request.ruleConfig());
        rule.setCron(request.cron());
        rule.setRemark(request.remark());
        return rule;
    }

    public record SaveRequest(
            @NotBlank(message = "规则名称不能为空") String name,
            Long datasourceId,
            @NotBlank(message = "目标表不能为空") String targetTable,
            @NotBlank(message = "规则类型不能为空") String ruleType,
            String columnName,
            String ruleConfig,
            String cron,
            String remark) {
    }

    public record UpdateRequest(
            @NotNull(message = "编号不能为空") Long id,
            @NotBlank(message = "规则名称不能为空") String name,
            Long datasourceId,
            @NotBlank(message = "目标表不能为空") String targetTable,
            @NotBlank(message = "规则类型不能为空") String ruleType,
            String columnName,
            String ruleConfig,
            String cron,
            String remark) {
    }
}
