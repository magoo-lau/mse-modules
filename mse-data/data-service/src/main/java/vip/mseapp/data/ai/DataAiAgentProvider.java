package vip.mseapp.data.ai;

import org.springframework.stereotype.Component;
import vip.mseapp.ai.api.AiAgentProvider;
import vip.mseapp.ai.api.dto.AiAgentDescriptor;

import java.util.List;

/**
 * 数据中台场景智能体声明：data_analyst「数据中台智能分析师」（中台库 ods_ 贴源表 + 白名单
 * 业务表受控查询）随服务启动自动登记 mse-ai 智能体管理（单体聚合形态 LocalAgentAutoRegistrar
 * 启动扫描直收；微服务形态经 ai-api 推送器周期重推，均幂等），提供方成为提示词权威——
 * 挂本模块反向工具 data_query（{@code DataQueryTools} 声明，编码全局一致）。
 *
 * <p>历史说明：biz_analyst「业务数据分析师」（业务库直查）原由本类声明——biz_query 模块化
 * 路由重构后查询通道收归 mse-ai 自持（业务模块各自提供查询工具，平台侧零业务库连接），
 * biz_analyst 声明迁移至 mse-ai（BuiltinBizAgentProvider，通用入口），本类不再声明。
 * 历史平台行由登记器按提供方权威自动刷新，存量租户副本行由 V20261011_03 刷新/退役。
 */
@Component
public class DataAiAgentProvider implements AiAgentProvider {

    @Override
    public List<AiAgentDescriptor> agents() {
        return List.of(
                agent("data_analyst", "数据中台智能分析师", "📊", "数据中台 text2sql 智能查询（受控通道：SQL 防护+租户隔离+限行，仅中台库）", """
                        你是数据中台分析 SQL 专家，帮助用户用自然语言查询数据中台的贴源数据。工作流程：
                        1) 先调用 data_query 工具（action=meta_search）检索相关表与列，拿不准列名时用 metaTableId 查列清单；
                        2) 再调用 data_query 工具（action=execute）执行只读 SELECT（不要写分页 LIMIT，系统自动处理）；
                        3) 用 Markdown 表格或要点向用户解读结果，说明数据口径与限制。
                        约束：只生成单条 SELECT；不查询敏感个人信息；中台库只查 ods_ 前缀贴源表（业务生产数据请使用业务分析师/各业务模块分析师）；
                        执行失败时根据报错修正 SQL 重试（最多 2 次）。
                        诚实性要求：回答必须基于工具返回的真实数据，禁止编造数据或假装已执行查询；
                        工具未能成功执行时，直接向用户说明失败原因与已尝试的 SQL，不要输出未经执行的查询结果。""", "data_query"));
    }

    private AiAgentDescriptor agent(String code, String name, String avatar, String description,
                                    String systemPrompt, String toolCodes) {
        AiAgentDescriptor descriptor = new AiAgentDescriptor();
        descriptor.setAgentCode(code);
        descriptor.setName(name);
        descriptor.setAvatar(avatar);
        descriptor.setDescription(description);
        descriptor.setSystemPrompt(systemPrompt);
        descriptor.setToolCodes(toolCodes);
        return descriptor;
    }
}
