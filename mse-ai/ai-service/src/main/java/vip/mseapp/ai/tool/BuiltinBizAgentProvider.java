package vip.mseapp.ai.tool;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.api.AiAgentProvider;
import vip.mseapp.ai.api.dto.AiAgentDescriptor;

import java.util.List;

/**
 * 平台内置智能体声明：业务分析师（biz_analyst）——跨模块业务数据问答的通用入口。
 * 绑定 biz_query 路由门面（list_modules 自描述路由）：先取模块目录，按问题判定归属模块，
 * 再在选中模块内检索表目录、执行受控查询。
 *
 * <p>历史说明：biz_analyst 原由 data-service 声明（业务库直查通道）；biz_query 模块化
 * 路由重构后收归 mse-ai 自持（查询委托各业务模块工具执行），声明随之迁移。历史平台行
 * 由登记器按提供方权威自动刷新，存量租户副本行由版本化脚本刷新。
 *
 * <p>直出图表（chartDirect=true，首个默认开启的数据分析师）：查询成功后仅一句话极简结论 +
 * 图表中文列（SQL 中文别名 + 枚举 CASE WHEN 中文映射），输出纪律由 AgentRuntime 运行时注入，
 * 租户可在智能体管理按副本关停；存量租户副本默认值由版本化脚本对齐。
 */
@Component
public class BuiltinBizAgentProvider implements AiAgentProvider {

    /** 通用入口系统提示词（与版本化迁移脚本 V20261011_03 刷新租户副本的文案保持一致） */
    public static final String BIZ_ANALYST_PROMPT = """
            你是业务数据分析师，负责回答跨业务模块的数据问题（审批、流程、工单、生产、公众号消息等）。

            工作流：
            1) 先调用 biz_query 工具（action=list_modules）获取可用业务模块目录（编码/名称/关键词）。
            2) 根据用户问题判断最匹配的模块，确定 module 参数取值；问题跨多个模块时逐模块分别查询。
            3) 调用 biz_query（action=meta_search，module=选中模块，keyword 传字符串数组一次传入所有可能的关键字：业务词加表名线索；严禁逐个关键字多次调用）。
            4) meta_search 返回的 tables 为空 = 该模块没有相关业务表，这是最终结论：立即停止调用任何工具，如实告知用户，可建议用户换用对应模块的专属分析师智能体。
            5) 检索到业务表后，用 action=table（携带 module 与 table 表名）查列清单，再调用 biz_query（action=execute，携带 module 与 sql）执行只读 SELECT 回答问题。

            约束：
            - meta_search/table/execute 都必须携带 module 参数；未注册模块会被拒绝并返回可用模块清单。
            - SQL 仅允许单条只读 SELECT：不要写分页 LIMIT（系统自动追加），不要写 deleted 条件（已删除数据系统已自动剔除）。
            - 系统表与平台数据（sys_/ai_ 等）不可查询。
            - 执行失败时根据报错修正 SQL 重试（最多 2 次），仍失败如实说明原因。
            - 回答用 Markdown 表格呈现数据，给出来源表名与统计口径说明。""";

    @Override
    public List<AiAgentDescriptor> agents() {
        return List.of(agent("biz_analyst", "业务分析师", "📈",
                "跨模块业务数据问答入口：自动识别问题归属的业务模块（协同办公/制造执行/微信公众号等），"
                        + "路由到对应模块业务库查询生产业务表数据。",
                BIZ_ANALYST_PROMPT, "biz_query", true));
    }

    private AiAgentDescriptor agent(String agentCode, String name, String avatar,
                                    String description, String systemPrompt, String toolCodes,
                                    boolean chartDirect) {
        AiAgentDescriptor descriptor = new AiAgentDescriptor();
        descriptor.setAgentCode(agentCode);
        descriptor.setName(name);
        descriptor.setAvatar(avatar);
        descriptor.setDescription(description);
        descriptor.setSystemPrompt(systemPrompt);
        descriptor.setToolCodes(toolCodes);
        descriptor.setChartDirect(chartDirect);
        return descriptor;
    }
}
