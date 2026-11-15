package vip.mseapp.search.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 租户字段覆盖（租户隔离表）：租户对单个源的查询层字段微调（可搜/权重/高亮/展示/显示名）。
 *
 * <p>仅覆盖查询参数，ES mapping 与索引数据仍由平台级配置（search_source.fields_json）决定；
 * 覆盖项只能引用平台已声明字段。tenantId 由 BaseEntity 携带（插件自动隔离与填充）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("search_tenant_field")
public class SearchTenantField extends BaseEntity {

    /** 源标识（须为平台已注册源） */
    private String sourceCode;

    /** 覆盖字段配置（JSON 数组，SearchFieldDescriptor 结构；field 匹配平台项，其余属性覆盖） */
    private String fieldsJson;
}
