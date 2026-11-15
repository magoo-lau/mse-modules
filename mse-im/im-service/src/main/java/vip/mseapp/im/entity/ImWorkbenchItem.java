package vip.mseapp.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 工作台入口配置（租户配置，聊天客户端宫格渲染）。
 * entry_type=0 时 path 为 mse 侧路由（如 /sub/mp/dashboard），客户端以 /system/profile/me 的
 * 菜单集做权限过滤后渲染；entry_type=1 时 path 为外部应用完整 http(s) URL，
 * auth_mode=1 时绑定开放平台应用（open_app_id + app_key 冗余列），免登授权凭证
 * （app_key/app_secret/域名白名单）归口 mse-open open_app，本表不落任何密钥。
 */
@TableName("im_workbench_item")
public class ImWorkbenchItem extends BaseEntity {

    private Long tenantId;

    /** 入口类型：0=mse 页面菜单 1=外部应用 */
    private Integer entryType;

    /** 入口地址：mse 侧路由（/sub/{micro}{path}）或外部应用完整 http(s) URL */
    private String path;

    /** 展示标题（type=0 缺省用菜单名；type=1 即入口名，必填） */
    private String title;

    /** 图标：emoji/字符，或 http(s) 图片 URL */
    private String icon;

    /** 宫格分组名（空=客户端按菜单树目录推导；外部应用缺省归「外部应用」） */
    private String groupName;

    /** 打开方式：0=内嵌 1=弹窗（新窗口） */
    private Integer openMode;

    /** 授权方式：0=直连 1=免登授权（授权码换 access_token） */
    private Integer authMode;

    /** 免登授权绑定的开放平台应用 app_key（保存时冗余落列，auth_mode=1 必填） */
    private String appKey;

    /** 关联开放平台应用 id（auth_mode=1 必填，凭证归口 open_app） */
    private Long openAppId;

    /** 可见角色编号集合（逗号分隔；NULL/空=全员可见，非空=交集才可见/可跳转） */
    private String roleIds;

    /** 排序（小在前） */
    private Integer sort;

    /** 0 启用 1 停用 */
    private Integer status;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Integer getEntryType() {
        return entryType;
    }

    public void setEntryType(Integer entryType) {
        this.entryType = entryType;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public Integer getOpenMode() {
        return openMode;
    }

    public void setOpenMode(Integer openMode) {
        this.openMode = openMode;
    }

    public Integer getAuthMode() {
        return authMode;
    }

    public void setAuthMode(Integer authMode) {
        this.authMode = authMode;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public Long getOpenAppId() {
        return openAppId;
    }

    public void setOpenAppId(Long openAppId) {
        this.openAppId = openAppId;
    }

    public String getRoleIds() {
        return roleIds;
    }

    public void setRoleIds(String roleIds) {
        this.roleIds = roleIds;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
