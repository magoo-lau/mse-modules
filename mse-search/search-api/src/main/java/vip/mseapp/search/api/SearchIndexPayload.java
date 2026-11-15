package vip.mseapp.search.api;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 索引事件载荷（MQ 通道与扫描通道共用的文档结构）。
 *
 * <p>个人数据过滤字段（与索引 mapping 对齐，业务按自身数据填充）：
 * ownerUserId=数据归属人（对应业务表 create_by/负责人列）、deptId=归属部门（对应 dept_id）、
 * visibleType=all 表示公开文档（全员可见，不参与范围过滤）、visibleUserIds=显式共享用户。
 * 租户归属经 TenantEvent 封装携带，本结构不重复携带。
 */
public class SearchIndexPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sourceCode;

    /** 业务主键（字符串化；与扫描通道 bizId 同一取值） */
    private String bizId;

    private String title;

    private String content;

    /** 附加字段（跳转模板 {extra.字段} 占位与结果展示） */
    private Map<String, Object> extra;

    /** 业务更新时间（ISO-8601，如 2026-09-18T10:00:00）；upsert 用于索引 updateTime 字段 */
    private String updateTime;

    /** true = 删除文档 */
    private boolean deleted;

    /** 数据归属人（个人数据过滤：SELF 范围按此过滤） */
    private Long ownerUserId;

    /** 归属部门（个人数据过滤：部门范围按此过滤） */
    private Long deptId;

    /** 可见类型：all = 公开文档全员可见；缺省 = 参与数据范围过滤 */
    private String visibleType;

    /** 显式共享用户集合（个人数据过滤：命中即可见） */
    private List<Long> visibleUserIds;

    public static SearchIndexPayload delete(String sourceCode, String bizId) {
        SearchIndexPayload payload = new SearchIndexPayload();
        payload.setSourceCode(sourceCode);
        payload.setBizId(bizId);
        payload.setDeleted(true);
        return payload;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
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
