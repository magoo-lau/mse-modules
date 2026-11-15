package vip.mseapp.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.monitor.entity.MonAlert;
import vip.mseapp.monitor.mapper.MonAlertMapper;
import vip.mseapp.monitor.service.AlertService;
import vip.mseapp.monitor.support.MonitorWorld;

/**
 * 监控告警中心（双端）：告警分页 + 手动处理。
 * 在线按会话各自隔离（平台看平台库/租户看本租户库）；离线为本地单一监控世界。
 */
@RestController
@RequestMapping("/monitor/alert")
public class MonitorAlertController {

    private final MonAlertMapper alertMapper;
    private final AlertService alertService;
    private final MonitorWorld world;

    public MonitorAlertController(MonAlertMapper alertMapper, AlertService alertService, MonitorWorld world) {
        this.alertMapper = alertMapper;
        this.alertService = alertService;
        this.world = world;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:alert:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:alert:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/page")
    public Result<PageResult<MonAlert>> page(@RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "20") long pageSize,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String keyword) {
        Page<MonAlert> page = world.scope(() -> alertMapper.selectPage(
                new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<MonAlert>()
                        .eq(status != null && !status.isBlank(), MonAlert::getStatus, status)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(MonAlert::getTargetName, keyword)
                                        .or().like(MonAlert::getContent, keyword))
                        .orderByDesc(MonAlert::getLastTriggerTime)));
        return Result.ok(PageResult.of(page.getTotal(), pageNum, pageSize, page.getRecords()));
    }

    /** 手动处理（firing 转 resolved + 恢复通知） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:alert:handle", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:alert:handle", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/{id}/handle")
    public Result<Void> handle(@PathVariable Long id) {
        alertService.handle(id);
        return Result.ok();
    }
}
