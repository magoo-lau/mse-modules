package vip.mseapp.open.api;

import java.io.Serializable;

/**
 * 应用凭证校验结果（mse-open 提供，业务积木经 {@link RemoteOpenAppService#checkCredential} 消费）。
 *
 * <p>校验范围：app_key + app_secret 常量时间比对 + 应用状态/过期检查（不含域名白名单，
 * 域名校验针对浏览器侧来源，走 {@link RemoteOpenAppService#domainAllowed}）。
 */
public class OpenAppCheck implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否通过 */
    private boolean valid;

    /** 应用编号（通过时返回） */
    private Long appId;

    /** 应用绑定租户（通过时返回） */
    private Long tenantId;

    /** 失败原因（通过时为 null；措辞已做防探测模糊，可直接展示） */
    private String reason;

    public static OpenAppCheck ok(Long appId, Long tenantId) {
        OpenAppCheck check = new OpenAppCheck();
        check.valid = true;
        check.appId = appId;
        check.tenantId = tenantId;
        return check;
    }

    public static OpenAppCheck invalid(String reason) {
        OpenAppCheck check = new OpenAppCheck();
        check.valid = false;
        check.reason = reason;
        return check;
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
