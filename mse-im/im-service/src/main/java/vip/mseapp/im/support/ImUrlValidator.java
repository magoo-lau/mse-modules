package vip.mseapp.im.support;

import org.springframework.stereotype.Component;
import vip.mseapp.core.exception.ServiceException;
import vip.mseapp.im.config.MseImProperties;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

/**
 * 租户自助录入实例地址的 SSRF 加固校验：仅允许 http/https 绝对地址、禁 userinfo 段；
 * 解析出的地址落在环回/内网/链路本地时默认拒绝，{@code mse.im.ssrf.allow-private-cidrs}
 * 白名单（IPv4 CIDR 或关键词 loopback）按部署放开（租户专线自建实例场景）。
 * 平台开通实例（ImProvisionService）的内部地址不经此校验。
 */
@Component
public class ImUrlValidator {

    private final MseImProperties properties;

    public ImUrlValidator(MseImProperties properties) {
        this.properties = properties;
    }

    public void validateApiUrl(String url) {
        validate(url, Set.of("http", "https"), "实例地址必须为绝对 http(s) 地址");
    }

    /** WS 地址按 ws/wss 校验（内网 WS 字段天然是 ws:// 协议，不能套 http 规则） */
    public void validateWsUrl(String url) {
        validate(url, Set.of("ws", "wss"), "WS 地址必须为绝对 ws(s):// 地址");
    }

    private void validate(String url, Set<String> schemes, String schemeMessage) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(400, schemeMessage);
        }
        if (uri.getScheme() == null || !schemes.contains(uri.getScheme().toLowerCase())
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ServiceException(400, schemeMessage);
        }
        if (uri.getUserInfo() != null) {
            throw new ServiceException(400, "实例地址不允许携带 userinfo");
        }
        List<String> allowlist = properties.getSsrf().getAllowPrivateCidrs();
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new ServiceException(400, "实例地址无法解析：" + uri.getHost());
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address) && !allowlisted(address, allowlist)) {
                throw new ServiceException(400, "实例地址指向内网/环回网段且未在 mse.im.ssrf.allow-private-cidrs 白名单内");
            }
        }
    }

    private static boolean isPrivate(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress();
    }

    private boolean allowlisted(InetAddress address, List<String> cidrs) {
        for (String cidr : cidrs) {
            String entry = cidr.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if ("loopback".equalsIgnoreCase(entry) && address.isLoopbackAddress()) {
                return true;
            }
            byte[] target = address.getAddress();
            if (target.length != 4) {
                continue; // CIDR 白名单仅支持 IPv4；IPv6 私有地址一律拒绝
            }
            String[] parts = entry.split("/");
            byte[] network = parseIPv4(parts[0]);
            if (network == null) {
                continue;
            }
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : 32;
            if (prefix < 0 || prefix > 32) {
                continue;
            }
            if (matches(network, target, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(byte[] network, byte[] target, int prefix) {
        int fullBytes = prefix / 8;
        int remainBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != target[i]) {
                return false;
            }
        }
        if (remainBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainBits);
        return (network[fullBytes] & mask) == (target[fullBytes] & mask);
    }

    private static byte[] parseIPv4(String value) {
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(parts[i]);
                if (octet < 0 || octet > 255) {
                    return null;
                }
                out[i] = (byte) octet;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
