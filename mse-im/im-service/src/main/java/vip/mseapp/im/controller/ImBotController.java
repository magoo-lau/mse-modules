package vip.mseapp.im.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.ai.api.RemoteAiAgentService;
import vip.mseapp.ai.api.dto.AiAgentOption;
import vip.mseapp.core.annotation.RpcReference;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.entity.ImBot;
import vip.mseapp.im.service.ImAvatarStorage;
import vip.mseapp.im.service.ImBotService;

import java.util.List;

/**
 * IM 机器人管理（租户端）：bot 账号绑定 mse-ai 智能体，保存即注册进本租户 OpenIM 实例。
 */
@Validated
@RestController
@RequestMapping("/im/bot")
public class ImBotController {

    private final ImBotService botService;

    private final ImAvatarStorage avatarStorage;

    /** 绑定智能体下拉数据源（本世界已发布智能体；租户上下文经 RPC Filter 透传） */
    @RpcReference
    private RemoteAiAgentService remoteAiAgentService;

    public ImBotController(ImBotService botService, ImAvatarStorage avatarStorage) {
        this.botService = botService;
        this.avatarStorage = avatarStorage;
    }

    @SaCheckPermission(value = "im:bot:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping
    public Result<List<ImBot>> list() {
        return Result.ok(botService.list());
    }

    /** 绑定智能体下拉（编码/名称/描述/头像），供新建/编辑机器人直接选择 */
    @SaCheckPermission(value = "im:bot:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/agent-options")
    public Result<List<AiAgentOption>> agentOptions() {
        return Result.ok(remoteAiAgentService.listOptions(null));
    }

    /** 机器人头像素材上传（≤2MB，png/jpg/jpeg/gif/webp），返回可写入 face_url 的站内地址 */
    @SaCheckPermission(value = "im:bot:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/asset")
    public Result<String> uploadAsset(@RequestParam("file") MultipartFile file) {
        return Result.ok(avatarStorage.upload(file));
    }

    @SaCheckPermission(value = "im:bot:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping
    public Result<ImBot> save(@Valid @RequestBody ImBotService.ImBotSaveRequest request) {
        return Result.ok(botService.save(request));
    }

    @SaCheckPermission(value = "im:bot:delete", type = MseConstants.LOGIN_TYPE_TENANT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        botService.delete(id);
        return Result.ok();
    }
}
