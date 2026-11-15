package vip.mseapp.search.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 增量文档（Provider 返回，每条自带租户归属——扫描链路跨租户查询，隔离在查询侧强制注入）。
 */
public class SearchDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文档归属租户（必填；0 = 平台公共数据） */
    private Long tenantId;

    /** 业务主键（字符串化，与 MQ 通道 bizId 同一取值） */
    private String bizId;

    private String title;

    private String content;

    /** 附加字段（跳转模板 {extra.字段} 占位与结果展示） */
    private Map<String, Object> extra;

    /** 业务更新时间（ISO-8601；增量列当前值，也是索引 updateTime 字段） */
    private String updateTime;

    /** true = 业务行已删除（搜索引擎据此删文档） */
    private boolean deleted;

    /** 数据归属人（个人数据过滤：SELF 范围按此过滤；对应业务表 create_by/负责人列） */
    private Long ownerUserId;

    /** 归属部门（个人数据过滤：部门范围按此过滤；对应业务表 dept_id 列） */
    private Long deptId;

    /** 可见类型：all = 公开文档全员可见；缺省 = 参与数据范围过滤 */
    private String visibleType;

    /** 显式共享用户集合（个人数据过滤：命中即可见） */
    private List<Long> visibleUserIds;

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getBizId() {
        return bizId;
    }

    public void setBizId(String bizId) {
        this.bizId = bizId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    public void setExtra(Map<String, Object> extra) {
        this.extra = extra;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public String getVisibleType() {
        return visibleType;
    }

    public void setVisibleType(String visibleType) {
        this.visibleType = visibleType;
    }

    public List<Long> getVisibleUserIds() {
        return visibleUserIds;
    }

    public void setVisibleUserIds(List<Long> visibleUserIds) {
        this.visibleUserIds = visibleUserIds;
    }
}
