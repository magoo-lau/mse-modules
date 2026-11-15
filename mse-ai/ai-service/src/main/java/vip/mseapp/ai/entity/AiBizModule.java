package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 业务模块注册（平台全局目录）：通用业务分析入口（biz_query 路由门面）按本表把问题路由到
 * 归属模块的查询工具，查询在模块自身进程内执行。业务积木经 AiBizModuleProvider 自声明
 * （单体本地扫描 source=local / 微服务推送 source=rpc，零 SQL 种子）；模块目录=全局目录，
 * 仅平台行（tenant_id=0），读取走 runIgnore，不建租户副本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_biz_module")
public class AiBizModule extends BaseEntity {

    /** 模块编码（世界内唯一，如 oa/mes/mp；biz_query 的 module 入参取值） */
    private String moduleCode;

    /** 模块名称（如「协同办公」「制造执行」） */
    private String name;

    /** 功能描述（模块业务范围一句话，供模型判定问题归属） */
    private String description;

    /** 归属服务名（spring.application.name，模块查询工具的 Dubbo group） */
    private String serviceName;

    /** 领域关键词（逗号分隔，如「审批,流程,请假」） */
    private String keywords;

    /** 业务表前缀（逗号分隔小写，如「oa_」；模块查询工具的放行白名单口径） */
    private String tablePrefixes;

    /** 模块查询工具编码（ai_tool.tool_code，如 oa_biz_query） */
    private String toolCode;

    /** 来源：local 单体声明登记 / rpc 微服务推送登记 */
    private String source;

    /** 声明提供方服务名（source=rpc 时登记，防同码互相覆盖） */
    private String rpcGroup;

    /** 状态：0 启用 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
