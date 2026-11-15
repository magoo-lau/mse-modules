package vip.mseapp.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * IM 实例台账（每租户一行，唯一键 tenant_id）：租户自助（TENANT_SELF_HOSTED）或
 * 平台开通（PLATFORM_HOSTED）写入；无行的租户回落 mse.im.* 配置。
 */
@TableName("im_instance_config")
public class ImInstanceConfig extends BaseEntity {

    /** 租户编号 */
    private Long tenantId;

    /** 来源：TENANT_SELF_HOSTED 租户自助 / PLATFORM_HOSTED 平台开通 */
    private String source;

    /** 服务端调用地址（mse-im → OpenIM admin REST） */
    private String apiUrl;

    /** 内网 WS 地址（预留连通性校验） */
    private String wsUrl;

    /** 下发客户端 apiAddr（缺省回落 api_url） */
    private String publicApiUrl;

    /** 下发客户端 wsAddr */
    private String publicWsUrl;

    /** 工作台跳转基址（mse Web 端地址；缺省回落全局 mse.im.web-base-url） */
    private String webBaseUrl;

    /** LiveKit 信令地址（音视频通话；缺省回落全局 mse.im.livekit.url） */
    private String livekitUrl;

    /** 实例管理员账号（share.yml imAdminUserID 内） */
    private String adminUserId;

    /** 实例 secret 密文（ImCrypt AES-256-GCM） */
    private String secretEnc;

    /** webhook 回调路由 token（URL 内嵌，弥补开源回调无签名） */
    private String callbackToken;

    /** 0 启用 1 停用（仓库惯例，同 sys_user/mp_account；停用 = 该租户 IM 显式关闭，不回落 yaml） */
    private Integer status;

    private LocalDateTime lastTestTime;

    /** 0 成功 1 失败（NULL 未测） */
    private Integer lastTestOk;

    private String lastTestMsg;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public String getPublicApiUrl() {
        return publicApiUrl;
    }

    public void setPublicApiUrl(String publicApiUrl) {
        this.publicApiUrl = publicApiUrl;
    }

    public String getPublicWsUrl() {
        return publicWsUrl;
    }

    public void setPublicWsUrl(String publicWsUrl) {
        this.publicWsUrl = publicWsUrl;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public String getLivekitUrl() {
        return livekitUrl;
    }

    public void setLivekitUrl(String livekitUrl) {
        this.livekitUrl = livekitUrl;
    }

    public String getAdminUserId() {
        return adminUserId;
    }

    public void setAdminUserId(String adminUserId) {
        this.adminUserId = adminUserId;
    }

    public String getSecretEnc() {
        return secretEnc;
    }

    public void setSecretEnc(String secretEnc) {
        this.secretEnc = secretEnc;
    }

    public String getCallbackToken() {
        return callbackToken;
    }

    public void setCallbackToken(String callbackToken) {
        this.callbackToken = callbackToken;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getLastTestTime() {
        return lastTestTime;
    }

    public void setLastTestTime(LocalDateTime lastTestTime) {
        this.lastTestTime = lastTestTime;
    }

    public Integer getLastTestOk() {
        return lastTestOk;
    }

    public void setLastTestOk(Integer lastTestOk) {
        this.lastTestOk = lastTestOk;
    }

    public String getLastTestMsg() {
        return lastTestMsg;
    }

    public void setLastTestMsg(String lastTestMsg) {
        this.lastTestMsg = lastTestMsg;
    }
}
