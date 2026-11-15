package vip.mseapp.search.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 可搜索源（租户隔离表，每租户一份目录）：业务经 search-api 自注册扇出播种（幂等 upsert），
 * 租户/平台各自管理扫描参数与启停。索引名由 indexPrefix + sourceCode + tenantId 推导。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("search_source")
public class SearchSource extends BaseEntity {

    /** 源标识（全局唯一，索引名与游标锚点） */
    private String sourceCode;

    /** 源名称（管理端与搜索结果分组展示） */
    private String sourceName;

    /** 业务域显示名（如「OA域」；空 = 不分域，仅参与「全部域」搜索） */
    private String domain;

    /** 数据提供者分组（业务服务名；dubbo 模式按此 group 寻址 Provider） */
    private String providerGroup;

    /** 跳转模板：/sub/{app}/... 支持 {bizId}、{extra.字段} 占位 */
    private String jumpUrlTemplate;

    /** 结果可见权限码（空 = 登录即可见；业务推送契约字段，重推刷新，旧字段保留兼容） */
    private String permissionCode;

    /** 可见角色编码（逗号分隔，空 = 不限制；用户持有任一角色才可见该源，与 permissionCode 同时生效；管理端调参维护，业务重推不覆盖） */
    private String visibleRoles;

    /** 是否启用个人数据范围过滤：0 关闭 1 开启 */
    private Integer dataScopeEnabled;

    /** 增量扫描 cron（秒 分 时 日 月 周） */
    private String scanCron;

    /** 增量水位列名（默认 update_time） */
    private String incrementalColumn;

    /** 每批拉取条数 */
    private Integer batchSize;

    /** 分片数 */
    private Integer shardCount;

    /** 状态：0 启用 1 停用（停用后不扫描、不出结果） */
    private Integer status;

    /** 平台级字段配置（JSON 数组，SearchFieldDescriptor 结构；空 = 无配置，查询退回内置 title/content） */
    private String fieldsJson;

    /** 字段配置定制标记：0 跟随推送刷新编码默认 1 管理端已定制（推送不覆盖） */
    private Integer fieldsCustomized;

    /** 说明 */
    private String remark;

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;

    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;
}
