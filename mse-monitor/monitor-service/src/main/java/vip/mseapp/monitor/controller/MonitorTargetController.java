package vip.mseapp.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
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
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.monitor.entity.MonTarget;
import vip.mseapp.monitor.engine.probe.ProbeResult;
import vip.mseapp.monitor.service.SlowCaptureService;
import vip.mseapp.monitor.service.TargetService;
import vip.mseapp.monitor.support.SlowCaptureSetting;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 监控目标管理（双端）：平台/租户按会话各自隔离。
 */
@Validated
@RestController
@RequestMapping("/monitor/target")
public class MonitorTargetController {

    private final TargetService targetService;
    private final SlowCaptureService slowCaptureService;

    public MonitorTargetController(TargetService targetService, SlowCaptureService slowCaptureService) {
        this.targetService = targetService;
        this.slowCaptureService = slowCaptureService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/page")
    public Result<PageResult<MonTarget>> page(@RequestParam(defaultValue = "1") long pageNum,
                                              @RequestParam(defaultValue = "50") long pageSize,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String targetType,
                                              @RequestParam(required = false) Integer enabled) {
        return Result.ok(targetService.page(pageNum, pageSize, keyword, targetType, enabled));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/{id}")
    public Result<MonTarget> get(@PathVariable Long id) {
        return Result.ok(targetService.get(id));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping
    public Result<Long> create(@RequestBody MonTarget target) {
        return Result.ok(targetService.create(target));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping
    public Result<Void> update(@RequestBody MonTarget target) {
        targetService.update(target);
        return Result.ok();
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam boolean enable) {
        targetService.changeStatus(id, enable);
        return Result.ok();
    }

    /** 立即检测（同步执行完整采集链路，返回本次结果） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:probe", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:probe", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/{id}/probe")
    public Result<ProbeResult> probeNow(@PathVariable Long id) {
        return Result.ok(targetService.probeNow(id));
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:delete", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:delete", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        targetService.delete(id);
        return Result.ok();
    }

    /** Redis 慢命令抓取开关状态（capturing=当前是否在抓取窗口内，过期窗口自动失效） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/{id}/slow-capture")
    public Result<Map<String, Object>> getSlowCapture(@PathVariable Long id) {
        requireRedisTarget(id);
        SlowCaptureSetting setting = slowCaptureService.get(id);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("capturing", slowCaptureService.capturing(id));
        status.put("enabled", setting.isEnabled());
        status.put("startAt", setting.getStartAt());
        status.put("endAt", setting.getEndAt());
        return Result.ok(status);
    }

    /**
     * 设置 Redis 慢命令抓取开关（排查工具，注意抓取期间每轮采集会读取目标 Redis 的 SLOWLOG）：
     * 开启二选一——minutes 持续时长（到期自动停）或 startAt/endAt 指定起止时间窗；关闭传 {"enabled":false}。
     */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:target:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/{id}/slow-capture")
    public Result<Map<String, Object>> setSlowCapture(@PathVariable Long id,
                                                      @RequestBody SlowCaptureRequest request) {
        requireRedisTarget(id);
        SlowCaptureSetting setting = slowCaptureService.set(
                id, request.enabled(), request.minutes(), request.startAt(), request.endAt());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("capturing", slowCaptureService.capturing(id));
        status.put("enabled", setting.isEnabled());
        status.put("startAt", setting.getStartAt());
        status.put("endAt", setting.getEndAt());
        return Result.ok(status);
    }

    private void requireRedisTarget(Long id) {
        MonTarget target = targetService.get(id);
        if (!MonTarget.TYPE_REDIS.equals(target.getTargetType())) {
            throw new ServiceException(400, "仅 Redis 类型的监控目标支持慢命令抓取");
        }
    }

    /** 慢命令抓取开关请求体 */
    public record SlowCaptureRequest(boolean enabled, Integer minutes, String startAt, String endAt) {
    }
}
