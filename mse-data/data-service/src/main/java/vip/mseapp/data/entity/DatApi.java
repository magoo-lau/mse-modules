package vip.mseapp.data.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 数据服务 API 定义：把一条 SELECT 封装成带鉴权/授权/限流的开放 HTTP 接口。
 *
 * <p>开放路径 {@code /openapi/data/{path}}；datasource_id=0 表示查中台贴源层
 * （仅允许 ods_ 前缀表并自动注入租户条件），否则查注册的外部数据源（数据源级隔离）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dat_api")
public class DatApi extends BaseEntity {

    /** 状态：草稿（新建/修改后，待提交审批） */
    public static final int STATUS_DRAFT = 0;
    /** 状态：待审批 */
    public static final int STATUS_PENDING = 1;
    /** 状态：已发布（可被开放调用） */
    public static final int STATUS_PUBLISHED = 2;
    /** 状态：已驳回 */
    public static final int STATUS_REJECTED = 3;
    /** 状态：已下线 */
    public static final int STATUS_OFFLINE = 4;

    /** API 名称 */
    private String name;

    /** 开放路径（/openapi/data/{path}），租户内唯一 */
    private String path;

    /** 查询的数据源（0 = 中台库贴源层） */
    private Long datasourceId;

    /** SQL 模板（仅 SELECT，:param 占位） */
    private String sqlTemplate;

    /** 参数定义 JSON [{name,type,required,remark}] */
    private String paramsJson;

    /** 分页开关：0 关 1 开（pageNum/pageSize） */
    private Integer pageEnabled;

    /** 单次返回行数上限 */
    private Integer maxRows;

    /** 查询超时（毫秒） */
    private Integer timeoutMs;

    /** 每分钟最大调用次数（0 = 跟随全局默认） */
    private Integer qpsLimit;

    /** 状态：0 发布 1 下线 */
    private Integer status;

    private String remark;
}
