package vip.mseapp.data.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.data.service.OverviewService;

/**
 * 数据中台概览（租户端首页）。
 */
@RestController
@RequestMapping("/data/overview")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @SaCheckPermission(value = "data:overview:list", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping
    public Result<OverviewService.Overview> overview() {
        return Result.ok(overviewService.overview());
    }
}
