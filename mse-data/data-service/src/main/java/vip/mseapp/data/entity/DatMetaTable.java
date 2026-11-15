package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;

/**
 * 元数据-表（数据地图条目）。
 *
 * <p>category=source 为从注册数据源采集的源库表；category=ods 为中台贴源层表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_meta_table")
public class DatMetaTable extends BaseEntity {

    /** 分类：源库表 */
    public static final String CATEGORY_SOURCE = "source";
    /** 分类：中台贴源表 */
    public static final String CATEGORY_ODS = "ods";

    /** 数据源编号（0 = 中台内部） */
    private Long datasourceId;

    /** 库名/schema */
    private String tableSchema;

    /** 表名 */
    private String tableName;

    /** 分类：source / ods */
    private String category;

    /** 表注释 */
    private String tableComment;

    /** 近似行数 */
    private Long rowCount;

    /** 最近采集时间 */
    private LocalDateTime collectedTime;
}
