package vip.mseapp.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vip.mseapp.mybatis.data.BaseEntity;

/**
 * 智能体（行级隔离）：
 * scope 为行创建时所在世界的标记（tenant=租户自有行；platform=平台世界行 tenant_id=0，
 * 读写随世界路由——一切皆租户）。种子智能体已经每租户播种为可编辑副本
 * （V20260926_02_ai__tenant_seed.sql），不再存在"平台公共 + 租户只读复制"模式；
 * scope=tenant 为租户自有智能体（tenant_id 归属，租户插件自动隔离）。
 *
 * <p>业务积木声明的智能体（source=local/rpc，经 AiAgentProvider 声明式注册）落平台行，
 * 各租户首次使用时懒补齐可编辑副本（区别于种子脚本的 builtin 播种通道）；平台行的
 * name/description/system_prompt/tool_codes 以提供方为权威（登记时幂等刷新），
 * 模型/温度/开场白/启停/备注由管理侧维护。
 *
 * <p>运行时绑定：模型（ai_model）+ 提示词 + 工具集（ai_tool.tool_code 列表）+ 知识库（ai_knowledge_base）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_agent")
public class AiAgent extends BaseEntity {

    /** 智能体编码（全局唯一，其他服务经此引用） */
    private String agentCode;

    /** 名称 */
    private String name;

    /** 头像（emoji 或 URL，前端展示） */
    private String avatar;

    /** 描述 */
    private String description;

    /** 系统提示词（支持 {{var}} 占位，调用方可传变量渲染） */
    private String systemPrompt;

    /** 绑定模型 ID（ai_model.id；为空回退平台默认模型） */
    private Long modelId;

    /** 温度覆盖（为空用模型默认） */
    private Double temperature;

    /** 开场白（前端展示） */
    private String welcomeMessage;

    /** 工具编码列表（逗号分隔 ai_tool.tool_code） */
    private String toolCodes;

    /** 知识库 ID 列表（逗号分隔 ai_knowledge_base.id；RAG 检索增强） */
    private String kbIds;

    /**
     * 声明绑定知识库编码列表（逗号分隔 ai_knowledge_base.code）：声明登记的权威字段
     * （随提供方刷新），租户副本懒补齐时按编码在本世界解析/创建库壳回填 kb_ids；
     * 租户手工绑定的 kb_ids 独立生效，两者运行期合并视角以 kb_ids 为准。
     */
    private String kbCodes;

    /**
     * 直出图表模式：0 关 1 开——开启后对话运行时在系统提示词末尾追加「一句话极简结论 +
     * 图表中文列」输出纪律，查询结果交由前端数据卡片直出（仅 simple 编排生效）。
     * 声明登记的智能体随提供方刷新平台行（AiAgentDescriptor.chartDirect），租户副本可编辑。
     */
    private Integer chartDirect;

    /** 编排模式：simple 简单配置（提示词+工具+知识库）/ flow 画布编排（graph_json 驱动） */
    private String orchestrationType;

    /** 画布编排 DSL（mse-flow/v1 JSON：nodes/edges；orchestration_type=flow 时生效）＝已发布版本，对话/RPC 运行读取 */
    private String graphJson;

    /** 画布编排草稿（编辑器保存目标；发布时校验并拷贝到 graph_json。空 = 无未发布草稿） */
    private String draftGraphJson;

    /** 归属范围：platform 平台公共 / tenant 租户自有 */
    private String scope;

    /** 归属租户（scope=platform 时为 0） */
    private Long tenantId;

    /** 状态：0 已发布 1 下线 */
    private Integer status;

    /** 来源：builtin 平台种子 / local 单体声明登记 / rpc 微服务推送登记 / custom 页面自建 */
    private String source;

    /** 声明提供方服务名（source=rpc 时登记，防多提供方同码互相覆盖；其余来源为空） */
    private String rpcGroup;

    /** 备注 */
    private String remark;
}
