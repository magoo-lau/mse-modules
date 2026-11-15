package vip.mseapp.search.api.dto;

import java.io.Serializable;

/**
 * 业务字段目录冲突条目：同名字段在不同来源中类型不一致（如两个表单的 field_x 一为 text 一为
 * keyword），无法安全合并为同一索引字段，目录侧整条排除不参与收录；detail 描述各来源与类型供排查。
 */
public class SearchFieldCatalogConflict implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 冲突字段名 */
    private String field;

    /** 冲突明细（形如「表单A(text)、表单B(keyword)」） */
    private String detail;

    public SearchFieldCatalogConflict() {
    }

    public SearchFieldCatalogConflict(String field, String detail) {
        this.field = field;
        this.detail = detail;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
