package vip.mseapp.open.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 应用域名白名单（逗号/分号/换行分隔）：条目为裸域名（example.com）或子域通配
 * （*.example.com，覆盖任意层级子域、不含裸域本身），不带协议/端口/路径；
 * 白名单为空 = 不限制。
 *
 * <p>请求来源域名取 Origin 头（优先，跨域请求浏览器必带）或 Referer 头（同源请求兜底）
 * 的 host 部分；已配置白名单但两头部均缺失（纯服务端调用）视为不命中——配置即生效，
 * 防去头绕过（fail-closed）。
 */
final class OpenDomainWhitelist {

    private OpenDomainWhitelist() {
    }

    /** @return true = 放行 */
    static boolean allowed(String whitelist, String host) {
        if (whitelist == null || whitelist.isBlank()) {
            return true;
        }
        if (host == null || host.isBlank()) {
            return false;
        }
        String target = host.toLowerCase();
        for (String entry : whitelist.split("[,;\\n]")) {
            String item = entry.trim().toLowerCase();
            if (item.isEmpty()) {
                continue;
            }
            if (item.startsWith("*.")) {
                // 子域通配：www.api.example.com / a.b.example.com 均以 .example.com 结尾
                if (target.endsWith(item.substring(1))) {
                    return true;
                }
            } else if (item.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 请求来源域名：Origin 优先，Referer 兜底；均缺失（或 Origin: null）返回 null */
    static String originHost(HttpServletRequest request) {
        String host = hostOf(request.getHeader("Origin"));
        if (host != null) {
            return host;
        }
        return hostOf(request.getHeader("Referer"));
    }

    /** 从 scheme://host[:port]/path 形态的 URL 提取 host（不含端口）；非该形态/空值返回 null */
    private static String hostOf(String url) {
        if (url == null || url.isBlank() || "null".equalsIgnoreCase(url.trim())) {
            return null;
        }
        String value = url.trim();
        int scheme = value.indexOf("://");
        if (scheme <= 0) {
            return null;
        }
        String rest = value.substring(scheme + 3);
        int path = rest.indexOf('/');
        String authority = path >= 0 ? rest.substring(0, path) : rest;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            // user-info 段剔除：按 URL 语义 @ 后才是 host（防 https://example.com@evil.com 形态混过比对）
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            // IPv6 字面量 [::1]:8080
            int close = authority.indexOf(']');
            return close > 0 ? authority.substring(1, close) : null;
        }
        int colon = authority.indexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        return host.isBlank() ? null : host;
    }
}
