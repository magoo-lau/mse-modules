package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 任务级血缘：同步任务的「源表 → ODS 目标表」关系（任务保存/修改时刷新，每任务一条）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_lineage")
public class DatLineage extends BaseEntity {

    /** 同步任务 */
    private Long jobId;

    /** 任务名称冗余 */
    private String jobName;

    /** 来源数据源 */
    private Long datasourceId;

    /** 来源类型：table / sql */
    private String sourceType;

    /** 来源表（sql 来源存查询摘要） */
    private String sourceTable;

    /** 目标 ODS 表 */
    private String targetTable;
}
