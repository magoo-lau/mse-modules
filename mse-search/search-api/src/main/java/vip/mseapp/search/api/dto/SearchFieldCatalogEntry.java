package vip.mseapp.search.api.dto;

import java.io.Serializable;

/**
 * 业务字段目录条目（业务 Provider fieldCatalog 返回的单个可匹配字段）。
 *
 * <p>field 即文档 extra 键（与供数端发射规则一致，[A-Za-z0-9_]）；label 为业务侧语义化显示名
 * （如控件标题）；type 为业务侧判定的索引类型（keyword/text/number/date），收录后为该字段的
 * mapping 类型依据，变更需全量重建；group 为字段来源分组（如表单名，多来源合并展示）。
 */
public class SearchFieldCatalogEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 字段名（= 文档 extra 键） */
    private String field;

    /** 显示名（业务侧语义标签） */
    private String label;

    /** 索引类型（keyword/text/number/date） */
    private String type;

    /** 来源分组（如表单名；多来源以「、」连接） */
    private String group;

    /** 携带该字段的来源数量（如表单数） */
    private Integer formCount;

    public SearchFieldCatalogEntry() {
    }

    public SearchFieldCatalogEntry(String field, String label, String type, String group, Integer formCount) {
        this.field = field;
        this.label = label;
        this.type = type;
        this.group = group;
        this.formCount = formCount;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public Integer getFormCount() {
        return formCount;
    }

    public void setFormCount(Integer formCount) {
        this.formCount = formCount;
    }
}
