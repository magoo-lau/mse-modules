package vip.mseapp.im.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImGroupService;

/**
 * 聊天客户端群组端点（登录态必选，经网关统一会话校验）：建群 / 邀请进群
 * （邀请权限在服务端按 OpenIM 群角色强制校验：仅群主/群管理员）。
 */
@Validated
@RestController
@RequestMapping("/im/client/group")
public class ImGroupController {

    private final ImGroupService groupService;

    public ImGroupController(ImGroupService groupService) {
        this.groupService = groupService;
    }

    /** 建群：群主=当前用户，成员/机器人多选（name 缺省按成员昵称生成） */
    @PostMapping
    public Result<ImGroupService.Created> create(@RequestBody(required = false) ImGroupService.GroupCreateRequest request) {
        return Result.ok(groupService.create(request));
    }

    /** 邀请进群：仅群主/群管理员可操作（服务端校验） */
    @PostMapping("/invite")
    public Result<Void> invite(@RequestBody(required = false) ImGroupService.InviteRequest request) {
        groupService.invite(request);
        return Result.ok();
    }
}
