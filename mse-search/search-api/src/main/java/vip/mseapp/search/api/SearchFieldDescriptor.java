package vip.mseapp.search.api;

import java.io.Serializable;

/**
 * 可搜索字段描述（源级字段元数据的编码默认值）。
 *
 * <p>业务经 {@link SearchSourceDescriptor#setFields(List)} 声明本源会随文档 extra 携带哪些字段
 * 及其默认用法；mse-search 落入平台级字段配置（search_source.fields_json）。管理端未定制
 * （fields_customized=0）时随推送刷新，一旦定制则不被覆盖——界面在编码默认之上调整。
 *
 * <p>约束：field 即文档 extra 中的键（索引时提升为顶层映射字段）；field 全局唯一于本源内，
 * 建议使用 [A-Za-z0-9_] 字符。type 决定 ES mapping，注册后修改类型需全量重建才对存量文档生效。
 */
public class SearchFieldDescriptor implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 字段类型：keyword 精确值 / text 全文（参与加权检索）/ number 数值 / date 日期 */
    public static final String TYPE_KEYWORD = "keyword";
    public static final String TYPE_TEXT = "text";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_DATE = "date";

    /** 字段名（= 文档 extra 键，[A-Za-z0-9_] 建议） */
    private String field;

    /** 显示名（配置界面与结果展示标签；空 = 用 field） */
    private String label;

    /** 类型（keyword/text/number/date；空 = keyword） */
    private String type;

    /** 是否参与全文检索（multi_match 加权字段；text/keyword 有意义） */
    private Boolean searchable;

    /** 检索权重（searchable 时生效，默认 1.0） */
    private Double weight;

    /** 是否参与高亮（text 类型有意义） */
    private Boolean highlightable;

    /** 是否随结果展示（搜索端按展示字段渲染 extra 值） */
    private Boolean returned;

    public SearchFieldDescriptor() {
    }

    public SearchFieldDescriptor(String field, String label, String type,
                                 Boolean searchable, Double weight, Boolean highlightable, Boolean returned) {
        this.field = field;
        this.label = label;
        this.type = type;
        this.searchable = searchable;
        this.weight = weight;
        this.highlightable = highlightable;
        this.returned = returned;
    }

    /** 便捷工厂：展示字段（returned，不参与检索） */
    public static SearchFieldDescriptor returned(String field, String label) {
        return new SearchFieldDescriptor(field, label, TYPE_KEYWORD, false, null, false, true);
    }

    /** 便捷工厂：全文检索字段 */
    public static SearchFieldDescriptor searchableText(String field, String label, double weight) {
        return new SearchFieldDescriptor(field, label, TYPE_TEXT, true, weight, true, true);
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

    public Boolean getSearchable() {
        return searchable;
    }

    public void setSearchable(Boolean searchable) {
        this.searchable = searchable;
    }

    public Double getWeight() {
        return weight;
    }

    public void setWeight(Double weight) {
        this.weight = weight;
    }

    public Boolean getHighlightable() {
        return highlightable;
    }

    public void setHighlightable(Boolean highlightable) {
        this.highlightable = highlightable;
    }

    public Boolean getReturned() {
        return returned;
    }

    public void setReturned(Boolean returned) {
        this.returned = returned;
    }
}
