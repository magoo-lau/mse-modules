package vip.mseapp.open.api;

import java.io.Serializable;

/**
 * 开放平台接入应用简表（mse-open 提供，业务积木经 {@link RemoteOpenAppService} 消费）。
 *
 * <p>仅暴露应用绑定/校验所需的最小字段集，AppSecret 密文/明文均不出开放平台域。
 */
public class OpenAppBrief implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 应用编号 */
    private Long id;

    /** 应用标识（全局唯一，接入方换 token 凭证之一） */
    private String appKey;

    /** 应用名称 */
    private String name;

    /** 绑定租户（0 = 平台域应用） */
    private Long tenantId;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
