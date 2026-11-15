package vip.mseapp.im.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImPresenceService;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * IM 在线状态查询（Redis presence，webhook afterUserOnline/Offline/KickOff 维护）。
 */
@RestController
@RequestMapping("/im/online")
public class ImOnlineController {

    private final ImPresenceService presenceService;

    public ImOnlineController(ImPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @SaCheckPermission(value = "im:instance:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping
    public Result<Map<String, Boolean>> filter(@RequestParam(required = false, defaultValue = "5") int platformId,
                                               @RequestParam(required = false) String userIds) {
        List<String> ids = userIds == null || userIds.isBlank()
                ? List.of()
                : Arrays.stream(userIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return Result.ok(presenceService.filterOnline(vip.mseapp.core.context.TenantContext.getRequiredTenantId(), platformId, ids));
    }

    @SaCheckPermission(value = "im:instance:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestParam(required = false, defaultValue = "5") int platformId) {
        return Result.ok(presenceService.listOnline(vip.mseapp.core.context.TenantContext.getRequiredTenantId(), platformId));
    }
}
