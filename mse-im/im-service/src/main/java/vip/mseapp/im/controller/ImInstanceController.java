package vip.mseapp.im.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.entity.ImInstanceConfig;
import vip.mseapp.im.service.ImInstanceService;

import java.util.Map;

/**
 * 租户自助 IM 实例配置（每租户一行）：secret 密文落库不回显，测试结果三列随行维护。
 */
@Validated
@RestController
@RequestMapping("/im/instance")
public class ImInstanceController {

    private final ImInstanceService instanceService;

    public ImInstanceController(ImInstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @SaCheckPermission(value = "im:instance:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping
    public Result<Map<String, Object>> get() {
        ImInstanceConfig row = instanceService.getOwn();
        // secret 密文绝不回显；callbackUrl 供租户回填实例 webhooks.yml
        return Result.ok(row == null
                ? Map.of("configured", false)
                : Map.of("configured", true, "instance", row, "callbackUrl", instanceService.callbackUrl()));
    }

    @SaCheckPermission(value = "im:instance:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping
    public Result<ImInstanceConfig> save(@Valid @RequestBody ImInstanceService.ImInstanceSaveRequest request) {
        return Result.ok(instanceService.saveOwn(request));
    }

    @SaCheckPermission(value = "im:instance:test", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/test")
    public Result<ImInstanceService.TestResult> test() {
        return Result.ok(instanceService.test());
    }
}
