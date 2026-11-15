package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 外部数据源注册（多租户：每个租户接入自己的业务库）。
 *
 * <p>与基座租户库路由（sys_tenant.ds_key）无关：这里是租户主动注册的「数据接入源」。
 * 口令 AES-GCM 加密落库，接口不回显，编辑留空表示不修改。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_datasource")
public class DatDatasource extends BaseEntity {

    /** 类型：MySQL */
    public static final String TYPE_MYSQL = "mysql";
    /** 类型：达梦 DM8 */
    public static final String TYPE_DM = "dm";
    /** 类型：人大金仓 KingbaseES */
    public static final String TYPE_KINGBASE = "kingbase";
    /** 类型：PostgreSQL（二期新增） */
    public static final String TYPE_POSTGRESQL = "postgresql";

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 数据源名称 */
    private String name;

    /** 类型：mysql / dm / kingbase */
    private String type;

    /** 主机 */
    private String host;

    /** 端口 */
    private Integer port;

    /** 库名/schema */
    private String dbName;

    /** JDBC 追加参数（如 useSSL=false&…） */
    private String extraParams;

    /** 用户名 */
    private String username;

    /** 口令密文（AES-GCM） */
    private String password;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 最近连通性测试时间 */
    private LocalDateTime lastTestTime;

    /** 最近测试结果：0 失败 1 成功 */
    private Integer lastTestOk;

    /** 最近测试失败原因 */
    private String lastTestMsg;

    private String remark;

    /** 可用类型集合（校验用） */
    public static final Set<String> SUPPORTED_TYPES =
            Set.of(TYPE_MYSQL, TYPE_DM, TYPE_KINGBASE, TYPE_POSTGRESQL);
}
