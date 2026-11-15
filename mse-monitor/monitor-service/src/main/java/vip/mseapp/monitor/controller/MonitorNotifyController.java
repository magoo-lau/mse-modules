package vip.mseapp.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.api.system.RemoteUserService;
import vip.mseapp.api.system.dto.UserDTO;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.PageResult;
import vip.mseapp.core.result.Result;
import vip.mseapp.monitor.service.NotifyService;
import vip.mseapp.monitor.service.NotifySettingService;
import vip.mseapp.monitor.support.NotifySetting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警通知设置（双端，界面可配）：站内信收件人 / Webhook 机器人，存 mon_setting 表（按会话租户隔离，各端各自维护）。
 */
@RestController
@RequestMapping("/monitor/notify")
public class MonitorNotifyController {

    private final NotifySettingService notifySettingService;
    private final NotifyService notifyService;

    @RpcReference
    private RemoteUserService userService;

    public MonitorNotifyController(NotifySettingService notifySettingService, NotifyService notifyService) {
        this.notifySettingService = notifySettingService;
        this.notifyService = notifyService;
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:notify:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:notify:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/setting")
    public Result<NotifySetting> setting() {
        return Result.ok(notifySettingService.current());
    }

    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:notify:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:notify:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PutMapping("/setting")
    public Result<Void> save(@RequestBody NotifySetting setting) {
        notifySettingService.save(setting);
        return Result.ok();
    }

    /** 发送测试通知（站内信 + Webhook 各通道实测），返回各通道结果 */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:notify:save", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:notify:save", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/test")
    public Result<Map<String, Object>> test() {
        return Result.ok(notifyService.sendTest());
    }

    /** 收件人选择器数据源：按用户名模糊检索（基座用户契约，启用状态前 20 条） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "monitor:notify:query", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "monitor:notify:query", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users(@RequestParam(required = false) String keyword) {
        PageResult<UserDTO> page = userService.pageUsers(1, 20,
                keyword == null || keyword.isBlank() ? null : keyword.trim(), 0, null);
        List<Map<String, Object>> users = new ArrayList<>();
        if (page.records() != null) {
            for (UserDTO user : page.records()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", user.getId());
                item.put("username", user.getUsername());
                item.put("nickname", user.getNickname());
                users.add(item);
            }
        }
        return Result.ok(users);
    }
}
