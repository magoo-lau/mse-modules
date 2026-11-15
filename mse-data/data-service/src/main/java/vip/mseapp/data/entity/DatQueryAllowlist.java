package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 中台库通道查询白名单：允许数据服务/AI 工具在 datasource_id=0 时
 * 查询 ods_ 贴源表之外的中台库业务表（两类表均自动注入租户条件）。
 *
 * <p>租户行级隔离：各租户维护各自的白名单，仅对本租户的中台库查询生效。
 * 独立库租户的白名单业务表在执行期路由至其租户专属库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_query_allowlist")
public class DatQueryAllowlist extends BaseEntity {

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 放行的表名（统一小写归一化落库，与 SQL 防护器比对规则一致） */
    private String tableName;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    private String remark;
}
