package vip.mseapp.open.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 开放 API 清单（目录：SQL 种子内置条目 + 业务积木自注册条目经 open-api 契约 upsert 维护）。
 *
 * <p>path 支持路径模板（如 /open/api/user/{id}），鉴权时按 HTTP 方法 + AntPathMatcher
 * 最长模式匹配；应用须在 open_app_api 中被授权对应 API 方可调用。
 * target 为空 = open-service 内置端点（精确映射 Controller 承接）；非空 = 泛化分发到
 * 业务契约（接口全名#方法名），mse-open 不依赖业务 jar。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("open_api")
public class OpenApiDef extends BaseEntity {

    /** 状态：启用 */
    public static final int STATUS_ENABLED = 0;
    /** 状态：停用 */
    public static final int STATUS_DISABLED = 1;

    /** 来源：SQL 种子 / 基座能力内置条目 */
    public static final String SOURCE_SEED = "seed";
    /** 来源：业务积木自注册（open-api 契约推送） */
    public static final String SOURCE_RPC = "rpc";

    /** API 权限标识（open:resource:action，授权与审计的锚点） */
    private String code;

    /** API 名称 */
    private String name;

    /** 路径模板（如 /open/api/user/{id}） */
    private String path;

    /** HTTP 方法（大写，如 GET/POST/PUT/DELETE；ALL=不限） */
    private String httpMethod;

    /** 分发目标：契约接口全名#方法名（空 = 内置端点） */
    private String target;

    /** 目录来源：seed=SQL 种子；rpc=业务积木自注册 */
    private String source;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    private String remark;
}
