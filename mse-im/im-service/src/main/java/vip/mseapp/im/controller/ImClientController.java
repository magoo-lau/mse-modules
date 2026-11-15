package vip.mseapp.im.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImAvatarStorage;
import vip.mseapp.im.service.ImProfileService;
import vip.mseapp.im.service.ImSessionService;
import vip.mseapp.im.service.ImShareService;

import java.io.IOException;
import java.util.List;

/**
 * 聊天客户端接入端点（登录态必选，经网关统一会话校验；平台 token 在服务内按 tenantId=0 拒绝）。
 * GET /asset/{name} 为头像本地兜底素材端点（免登录：img 标签不携带 token；
 * 微服务形态需网关 mse.gateway.ignore-urls 放行；mse-fs 模式不走本端点）。
 */
@Validated
@RestController
@RequestMapping("/im/client")
public class ImClientController {

    private final ImSessionService sessionService;
    private final ImProfileService profileService;
    private final ImAvatarStorage avatarStorage;
    private final ImShareService shareService;

    public ImClientController(ImSessionService sessionService, ImProfileService profileService,
                              ImAvatarStorage avatarStorage, ImShareService shareService) {
        this.sessionService = sessionService;
        this.profileService = profileService;
        this.avatarStorage = avatarStorage;
        this.shareService = shareService;
    }

    /** IM 会话签发：userID/imToken/客户端连接地址随登录态下发（platformID 缺省 5=Web） */
    @PostMapping("/session")
    public Result<ImSessionService.SessionVO> session(@RequestBody(required = false) SessionRequest request) {
        return Result.ok(sessionService.session(request == null ? null : request.platformId()));
    }

    record SessionRequest(@Min(1) @Max(7) Integer platformId) {
    }

    /**
     * 页面分享：把基座页面卡片（app-share custom 消息）以当前用户身份发给联系人/群聊。
     * 工作台内嵌页（聊天客户端）的「分享」入口调用；群目标要求发送人在群内。
     */
    @PostMapping("/share")
    public Result<Integer> share(@Valid @RequestBody ShareRequest request) {
        return Result.ok(shareService.share(request.userIds(), request.groupIds(),
                request.title(), request.desc(), request.url(), request.icon()));
    }

    record ShareRequest(List<Long> userIds, List<String> groupIds,
                        @NotBlank @Size(max = 64) String title,
                        @Size(max = 128) String desc,
                        @NotBlank @Size(max = 500) String url,
                        @Size(max = 255) String icon) {
    }

    /** 聊天对象资料卡：基座资料 + 部门路径 + 生效头像（机器人资料客户端走通讯录 bots 集，不设端点） */
    @GetMapping("/user/{userId}/profile")
    public Result<ImProfileService.ProfileVO> userProfile(@PathVariable Long userId) {
        return Result.ok(profileService.profile(userId));
    }

    /** 上传自设头像（≤2MB，png/jpg/jpeg/gif/webp），返回生效头像地址（IM 自设 > 基座 avatar） */
    @PostMapping("/profile/avatar")
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return Result.ok(profileService.uploadAvatar(file));
    }

    /** 重置自设头像：回落基座 sys_user.avatar，返回生效头像地址（null=客户端首字母兜底） */
    @PostMapping("/profile/avatar/reset")
    public Result<String> resetAvatar() {
        return Result.ok(profileService.resetAvatar());
    }

    /** 头像本地兜底素材输出（免登录；文件名白名单定型，公开无敏感数据） */
    @GetMapping("/asset/{name}")
    public void asset(@PathVariable String name, HttpServletResponse response) throws IOException {
        avatarStorage.write(name, response);
    }
}
