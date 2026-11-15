package vip.mseapp.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.monitor.entity.MonRecord;
import vip.mseapp.monitor.service.RecordService;

import java.util.List;

/**
 * 监控采样记录（双端）：目标详情页趋势图数据源。
 */
@RestController
@RequestMapping("/monitor/record")
public class MonitorRecordController {

    private final RecordService recordService;

    public MonitorRecordController(RecordService recordService) {
        this.recordService = recordService;
    }

    /** 目标趋势数据（hours=1/24/168，升序，metrics 为按类型指标集 JSON） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/list")
    public Result<List<MonRecord>> list(@RequestParam Long targetId,
                                        @RequestParam(defaultValue = "1") int hours) {
        return Result.ok(recordService.list(targetId, hours));
    }
}
