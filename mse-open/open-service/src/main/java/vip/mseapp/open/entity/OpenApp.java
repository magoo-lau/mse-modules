package vip.mseapp.open.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 开放平台接入应用（全局表，平台端维护）。
 *
 * <p>tenant_id（继承 BaseEntity）为「业务绑定租户」语义（应用调用开放 API 时数据被隔离在该租户域内），
 * 非租户插件的隔离列——本表已列入租户插件白名单，平台管理员跨租户管理。
 * AppSecret 经 AES-GCM 加密落库，明文仅创建/重置时返回一次。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("open_app")
public class OpenApp extends BaseEntity {

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 应用标识（接入方换 token 凭证之一，全局唯一，自动生成） */
    private String appKey;

    /** AppSecret 密文（AES-GCM） */
    private String appSecret;

    /** 应用名称 */
    private String name;

    /** 限流 QPS（NULL=使用 mse.open.default-rate-limit） */
    private Long rateLimitQps;

    /** 凭证过期时间（NULL=长期有效；过期后换 token/鉴权均拒绝） */
    private java.time.LocalDateTime expireTime;

    /** 强制请求签名验签：0 关 1 开（HMAC-SHA256 + 时间戳防重放 + nonce，见 OpenSignatureService） */
    private Integer signRequired;

    /** IP 白名单（逗号/分号/换行分隔，支持 IPv4 CIDR；空 = 不限制） */
    private String ipWhitelist;

    /** 域名白名单（逗号/分号/换行分隔，裸域名或 *.example.com 子域通配；空 = 不限制） */
    private String domainWhitelist;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    private String remark;
}
