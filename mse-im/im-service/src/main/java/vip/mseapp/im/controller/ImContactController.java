package vip.mseapp.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.result.Result;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.im.service.ImContactService;

import java.util.List;

/**
 * 聊天客户端通讯录端点（登录态必选，经网关统一会话校验）：部门/用户/机器人一次拉全 + 按需注册。
 */
@Validated
@RestController
@RequestMapping("/im/client/contacts")
public class ImContactController {

    private final ImContactService contactService;

    public ImContactController(ImContactService contactService) {
        this.contactService = contactService;
    }

    /** 通讯录全量：depts（部门树数据，leaderUserId=负责人）+ users（启用用户）+ bots（启用机器人，公众号式分组） */
    @SaCheckLogin(type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping
    public Result<ImContactService.ContactVO> contacts() {
        return Result.ok(contactService.contacts());
    }

    /** 按需注册：对未登录过 IM 的用户先注册进实例（发起单聊/建群前兜底） */
    @SaCheckLogin(type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/ensure")
    public Result<Integer> ensure(@RequestBody ImContactService.EnsureRequest request) {
        return Result.ok(contactService.ensureUsers(request == null ? null : request.userIds()));
    }
}
