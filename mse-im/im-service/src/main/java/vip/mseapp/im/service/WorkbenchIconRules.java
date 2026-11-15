package vip.mseapp.im.service;

import org.springframework.web.multipart.MultipartFile;
import vip.mseapp.core.exception.ServiceException;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 工作台图标上传校验与常量：扩展名白名单（按扩展名定型 Content-Type，不信任上传方声明）、
 * 大小上限与落盘文件名白名单（与基座头像同口径）。
 */
final class WorkbenchIconRules {

    private WorkbenchIconRules() {
    }

    /** 单文件大小上限（2MB，宫格小图够用） */
    static final long MAX_SIZE = 2 * 1024 * 1024L;

    /** 扩展名 → Content-Type */
    static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    /** 落盘文件名白名单（{雪花id}.{扩展名}，防路径穿越） */
    static final Pattern ASSET_NAME_PATTERN = Pattern.compile("^\\d+\\.(png|jpg|jpeg|gif|webp)$");

    private static final String ALLOWED = "png/jpg/jpeg/gif/webp";

    /** 校验上传文件，返回定型扩展名 */
    static String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException("请选择图标图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ServiceException("图标不能超过 2MB");
        }
        String ext = extension(file.getOriginalFilename());
        if (!CONTENT_TYPES.containsKey(ext)) {
            throw new ServiceException("仅支持 " + ALLOWED + " 格式");
        }
        return ext;
    }

    /** 小写扩展名（无扩展名返回空串） */
    static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
