package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 元数据-列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_meta_column")
public class DatMetaColumn extends BaseEntity {

    /** 所属表（dat_meta_table.id） */
    private Long metaTableId;

    /** 列名 */
    private String columnName;

    /** 数据类型 */
    private String dataType;

    /** 是否可空：0 否 1 是 */
    private Integer nullable;

    /** 列注释 */
    private String columnComment;

    /** 序号（从 1 开始） */
    private Integer ordinal;
}
