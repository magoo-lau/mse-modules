package vip.mseapp.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.monitor.service.DashboardService;

import java.util.Map;

/**
 * 监控总览（双端）：平台/租户按会话各自隔离。
 */
@RestController
@RequestMapping("/monitor/dashboard")
public class MonitorDashboardController {

    private final DashboardService dashboardService;

    public MonitorDashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:dashboard:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:dashboard:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        return Result.ok(dashboardService.summary());
    }
}
