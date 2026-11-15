package vip.mseapp.im.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;
import vip.mseapp.core.constant.MseConstants;
import vip.mseapp.core.result.Result;
import vip.mseapp.im.service.ImWorkbenchService;
import vip.mseapp.im.service.WorkbenchIconStorage;
import vip.mseapp.open.api.OpenAppBrief;
import vip.mseapp.security.stp.StpTenantUtil;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 工作台入口配置。
 * GET 供客户端拉取（登录态即可，仅启用行；all=true 供配置页拉全量，需 im:workbench:query）；
 * PUT 保存 / 图标上传为租户管理动作（im:workbench:save）；免登授权入口凭证归口开放平台
 * open_app（配置页下拉候选经 GET /open-apps，本控制器不再提供任何密钥端点）；
 * POST /jump 供客户端点击外部应用入口时签发跳转链接（登录态即可）；
 * GET /asset/{name} 为图标本地兜底素材端点（免登录，img 标签不携带 token；微服务形态需网关白名单）。
 */
@Validated
@RestController
@RequestMapping("/im/workbench")
public class ImWorkbenchController {

    private final ImWorkbenchService workbenchService;
    private final WorkbenchIconStorage iconStorage;

    public ImWorkbenchController(ImWorkbenchService workbenchService, WorkbenchIconStorage iconStorage) {
        this.workbenchService = workbenchService;
        this.iconStorage = iconStorage;
    }

    @GetMapping
    public Result<ImWorkbenchService.WorkbenchVO> view(@RequestParam(defaultValue = "false") boolean all) {
        if (all) {
            StpTenantUtil.stpLogic.checkPermission("im:workbench:query");
        }
        return Result.ok(workbenchService.view(all));
    }

    @SaCheckPermission(value = "im:workbench:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PutMapping
    public Result<Void> save(
            @Valid @RequestBody Map<String, List<ImWorkbenchService.ItemSave>> body) {
        workbenchService.save(body.get("items"));
        return Result.ok();
    }

    /** 本租户开放平台应用清单（免登授权入口下拉候选，含停用行由前端标注禁选） */
    @SaCheckPermission(value = "im:workbench:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/open-apps")
    public Result<List<OpenAppBrief>> openApps() {
        return Result.ok(workbenchService.listOpenApps());
    }

    /** 本租户启用角色清单（入口「可见角色」下拉候选） */
    @SaCheckPermission(value = "im:workbench:query", type = MseConstants.LOGIN_TYPE_TENANT)
    @GetMapping("/roles")
    public Result<List<ImWorkbenchService.RoleBrief>> roles() {
        return Result.ok(workbenchService.listRoles());
    }

    /** 外部应用入口跳转：直连返回原 URL；免登授权校验域名白名单后签发一次性授权码并拼到回调地址 */
    @PostMapping("/jump")
    public Result<ImWorkbenchService.JumpVO> jump(@RequestBody Map<String, Object> body) {
        Object id = body == null ? null : body.get("id");
        if (id == null || id.toString().isBlank()) {
            return Result.fail(400, "缺少入口 id");
        }
        return Result.ok(workbenchService.jump(Long.valueOf(id.toString())));
    }

    /** 上传工作台入口图标（≤2MB，png/jpg/jpeg/gif/webp），返回可写入 icon 字段的站内地址 */
    @SaCheckPermission(value = "im:workbench:save", type = MseConstants.LOGIN_TYPE_TENANT)
    @PostMapping("/asset")
    public Result<String> uploadAsset(@RequestParam("file") MultipartFile file) {
        return Result.ok(iconStorage.upload(file));
    }

    /** 图标本地兜底素材输出（免登录：img 标签不携带 token；mse-fs 模式不走本端点） */
    @GetMapping("/asset/{name}")
    public void asset(@PathVariable String name, HttpServletResponse response) throws IOException {
        iconStorage.write(name, response);
    }
}
