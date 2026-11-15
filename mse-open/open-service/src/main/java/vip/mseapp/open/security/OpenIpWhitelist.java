package vip.mseapp.open.security;

/**
 * 应用 IP 白名单（逗号/分号/换行分隔）：条目支持精确 IP 与 IPv4 CIDR（如 10.0.0.0/8）；
 * 白名单为空 = 不限制。IPv6 条目按精确匹配处理。
 */
final class OpenIpWhitelist {

    private OpenIpWhitelist() {
    }

    /** @return true = 放行 */
    static boolean allowed(String whitelist, String ip) {
        if (whitelist == null || whitelist.isBlank()) {
            return true;
        }
        if (ip == null || ip.isBlank()) {
            return false;
        }
        for (String entry : whitelist.split("[,;\\n]")) {
            String item = entry.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (item.contains("/")) {
                if (cidrMatches(item, ip)) {
                    return true;
                }
            } else if (item.equalsIgnoreCase(ip)) {
                return true;
            }
        }
        return false;
    }

    /** IPv4 CIDR 匹配（非 IPv4 条目/地址不命中，回退走精确匹配） */
    private static boolean cidrMatches(String cidr, String ip) {
        int slash = cidr.indexOf('/');
        String network = cidr.substring(0, slash).trim();
        int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long net = ipv4ToLong(network);
        long addr = ipv4ToLong(ip);
        if (net < 0 || addr < 0 || prefix < 0 || prefix > 32) {
            return false;
        }
        if (prefix == 0) {
            return true;
        }
        long mask = 0xFFFFFFFFL << (32 - prefix);
        return (net & mask) == (addr & mask);
    }

    /** IPv4 → 无符号长整型；非 IPv4 返回 -1 */
    private static long ipv4ToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return -1;
        }
        long value = 0;
        for (String part : parts) {
            try {
                int octet = Integer.parseInt(part.trim());
                if (octet < 0 || octet > 255) {
                    return -1;
                }
                value = (value << 8) | octet;
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return value;
    }
}
