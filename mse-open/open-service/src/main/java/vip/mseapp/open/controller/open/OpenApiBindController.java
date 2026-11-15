package vip.mseapp.open.controller.open;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.result.Result;
import vip.mseapp.open.service.OpenBindService;

import java.util.List;

/**
 * 统一身份绑定开放 API（/open/api/bind/**，应用 access_token 鉴权，租户域 = 应用绑定租户）。
 *
 * <p>接入系统用自家用户标识（sourceSystem + externalUserId）换取中台账号，
 * 实现"一套账号多系统通行"。同一外部标识租户内唯一。
 */
@Validated
@RestController
@RequestMapping("/open/api/bind")
public class OpenApiBindController {

    private final OpenBindService bindService;

    public OpenApiBindController(OpenBindService bindService) {
        this.bindService = bindService;
    }

    @PostMapping
    public Result<OpenBindService.BindCreated> bind(@RequestBody @Validated BindRequest request) {
        return Result.ok(bindService.bind(request.userId(), request.sourceSystem(),
                request.externalUserId(), request.remark()));
    }

    /** 按外部标识解析中台账号；未绑定返回 data=null */
    @GetMapping("/resolve")
    public Result<OpenBindService.BindResolve> resolve(@RequestParam String sourceSystem,
                                                       @RequestParam String externalUserId) {
        return Result.ok(bindService.resolve(sourceSystem, externalUserId));
    }

    /** 一个中台账号的全部外部身份 */
    @GetMapping("/by-user/{userId}")
    public Result<List<vip.mseapp.open.entity.OpenUserBind>> byUser(@PathVariable Long userId) {
        return Result.ok(bindService.listByUser(userId));
    }

    @DeleteMapping("/{bindId}")
    public Result<Void> unbind(@PathVariable Long bindId) {
        bindService.unbind(bindId);
        return Result.ok();
    }

    public record BindRequest(
            @NotNull(message = "userId 不能为空") Long userId,
            @NotBlank(message = "sourceSystem 不能为空") String sourceSystem,
            @NotBlank(message = "externalUserId 不能为空") String externalUserId,
            String remark) {
    }
}
