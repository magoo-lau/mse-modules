package vip.mseapp.fs.controller;

import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.context.TenantContext;
import vip.mseapp.core.context.UserContext;
import vip.mseapp.core.result.Result;
import vip.mseapp.fs.service.FsStorageMigrationService;

import java.util.Map;

/**
 * 存储运维（双端会话）：历史对象跨实现迁移（local ↔ rustfs）。
 *
 * <p>目标 = 当前装配的存储实现（mse.fs.storage.type），源按 from 参数动态构建；
 * 同步执行，文件量大时建议低峰操作。迁移范围随会话世界收口：租户会话仅迁移本租户文件，
 * 平台会话全库迁移（迁移是全局运维动作，不留给租户会话跨租户触发）。
 */
@RestController
@RequestMapping("/fs/storage")
public class FsStorageController {

    private final FsStorageMigrationService migrationService;

    public FsStorageController(FsStorageMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /** 迁移历史对象到当前存储（from：local / rustfs，须与当前实现不同；租户会话仅本租户范围） */
    @SaCheckOr(permission = {
            @SaCheckPermission(value = "fs:storage:migrate", type = MseConstants.LOGIN_TYPE_TENANT),
            @SaCheckPermission(value = "fs:storage:migrate", type = MseConstants.LOGIN_TYPE_PLATFORM)})
    @PostMapping("/migrate")
    public Result<Map<String, Object>> migrate(@RequestParam("from") String from) {
        Long ctx = TenantContext.getTenantId();
        // 租户会话锁定本租户范围；平台会话（tenant 0）全库迁移
        boolean tenantSession = !UserContext.isPlatform() && ctx != null && ctx != 0;
        return Result.ok(migrationService.migrate(from, tenantSession ? ctx : null));
    }
}
