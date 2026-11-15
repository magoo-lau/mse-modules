package vip.mseapp.im.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImDeptGroupService;

import java.util.List;

/**
 * 部门群管理端点（租户端）：映射列表查看 + 手动全量对账。
 * 群主=部门负责人（基座「系统管理→部门管理」设置），不在此提供换群主入口。
 */
@Validated
@RestController
@RequestMapping("/im/dept-group")
public class ImDeptGroupController {

    private final ImDeptGroupService deptGroupService;

    public ImDeptGroupController(ImDeptGroupService deptGroupService) {
        this.deptGroupService = deptGroupService;
    }

    /** 映射列表：部门/群名/群主/成员数（复用 IM 实例配置查看权限） */
    @SaCheckPermission(value = "im:instance:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping
    public Result<List<ImDeptGroupService.DeptGroupVO>> list() {
        return Result.ok(deptGroupService.listView());
    }

    /** 手动全量对账：新部门建群/改名同步/成员补拉与踢出/群主随负责人转让/已删部门解散 */
    @SaCheckPermission(value = "im:instance:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/sync")
    public Result<ImDeptGroupService.ReconcileSummary> sync() {
        return Result.ok(deptGroupService.manualSync());
    }
}
