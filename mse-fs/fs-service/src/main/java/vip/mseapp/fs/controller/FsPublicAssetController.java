package vip.mseapp.fs.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mseapp.fs.api.FsAssetUrls;
import vip.mseapp.fs.service.FsFileService;

import java.io.IOException;

/**
 * 公开素材读取（免登录）：URL 规范见 {@link FsAssetUrls}——首段租户号定位素材所属世界
 * （0=平台世界，其余为租户编号），服务端按该段做语句级路由寻址，仅放行 access_level=public
 * 的文件，登录页 Logo/背景图等未登录场景专用。尾段允许带真实后缀（如 {@code 123.png}，
 * 纯可读性，解析时剥离；不带后缀同语义）。服务端 Sa-Token 为注解式鉴权，本控制器无注解
 * 即匿名可达；微服务形态需在网关 mse.gateway.ignore-urls 放行 /fs/asset/**。
 */
@RestController
@RequestMapping("/fs/asset")
public class FsPublicAssetController {

    private final FsFileService fileService;

    public FsPublicAssetController(FsFileService fileService) {
        this.fileService = fileService;
    }

    /** 按租户世界 + fileId 输出素材内容（inline + 永久强缓存；非公开/跨世界/不存在同语义 404） */
    @GetMapping("/{tenantId}/{fileId:.+}")
    public void asset(@PathVariable long tenantId, @PathVariable String fileId,
                      HttpServletResponse response) throws IOException {
        long id = parseFileId(fileId);
        // 非法段（负数/非数字/坏后缀）按不存在处理，不做路由解析（微服务形态省一次无谓的 RPC 寻址）
        if (tenantId < 0 || id < 0) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        fileService.downloadPublicAsset(tenantId, id, response);
    }

    /**
     * fileId 段解析：{@code 123.png} 剥掉一段字母数字后缀后按雪花 id 解析；
     * 无后缀 {@code 123} 同样接受；非数字或坏后缀返回 -1（按 404 处理）。
     */
    private long parseFileId(String segment) {
        int dot = segment.lastIndexOf('.');
        String id = segment;
        if (dot > 0) {
            if (!segment.substring(dot + 1).matches("[A-Za-z0-9]{1,16}")) {
                return -1;
            }
            id = segment.substring(0, dot);
        }
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
