package vip.mseapp.im.support;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 工作台免登授权安全工具：一次性 code / access_token 生成与常量时间比对、URL 参数拼接。
 * 供 {@code ImWorkbenchService}（签发授权码）与 {@code ImOauthService}（兑换 token）共用；
 * 凭证生成/摘要已归口开放平台（open_app），本类不再持有密钥类工具。
 */
public final class WbSec {

    /** 共享 JsonMapper（record payload 存取） */
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static final SecureRandom RANDOM = new SecureRandom();

    private WbSec() {
    }

    /** 一次性授权码：24 随机字节 Base64url（无填充） */
    public static String newCode() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** access_token：32 随机字节 Base64url（无填充） */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 常量时间字符串比对（防时序侧信道） */
    public static boolean constantEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 向 URL 追加参数：自动判 ?/& */
    public static String appendParam(String url, String param) {
        return url + (url.contains("?") ? "&" : "?") + param;
    }
}
