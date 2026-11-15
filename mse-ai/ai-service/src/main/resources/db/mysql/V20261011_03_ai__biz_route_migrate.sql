-- mse-target: master
SET NAMES utf8mb4;
-- =====================================================================
-- biz_query 模块化路由迁移：biz_query 从 data-service RPC 工具收归 mse-ai 自持路由门面，
-- 查询委托各业务模块自提供的查询工具在模块进程内执行（平台侧零业务库连接）。
-- 幂等：全部条件 UPDATE，重复应用无效果。
-- =====================================================================

-- ① biz_query 工具归 mse-ai 自持（source rpc/mse-data → builtin；租户副本行一并归一；
--    工具中心启停状态/备注保留不动）
UPDATE ai_tool
SET source = 'builtin',
    rpc_group = NULL
WHERE tool_code = 'biz_query'
  AND deleted = 0
  AND (source <> 'builtin' OR rpc_group IS NOT NULL);

-- ② biz_analyst 通用入口租户副本刷新（平台行由 mse-ai 内置提供方 BuiltinBizAgentProvider
--    启动登记按提供方权威自动刷新；租户副本可编辑、不自动跟随，此处把仍绑定 biz_query 的
--    声明副本对齐新工作流，不碰租户自建 custom 行与已改名行）
UPDATE ai_agent
SET name          = '业务分析师',
    tool_codes    = 'biz_query',
    system_prompt = '你是业务数据分析师，负责回答跨业务模块的数据问题（审批、流程、工单、生产、公众号消息等）。\n\n工作流：\n1) 先调用 biz_query 工具（action=list_modules）获取可用业务模块目录（编码/名称/关键词）。\n2) 根据用户问题判断最匹配的模块，确定 module 参数取值；问题跨多个模块时逐模块分别查询。\n3) 调用 biz_query（action=meta_search，module=选中模块，keyword 传字符串数组一次传入所有可能的关键字：业务词加表名线索；严禁逐个关键字多次调用）。\n4) meta_search 返回的 tables 为空 = 该模块没有相关业务表，这是最终结论：立即停止调用任何工具，如实告知用户，可建议用户换用对应模块的专属分析师智能体。\n5) 检索到业务表后，用 action=table（携带 module 与 table 表名）查列清单，再调用 biz_query（action=execute，携带 module 与 sql）执行只读 SELECT 回答问题。\n\n约束：\n- meta_search/table/execute 都必须携带 module 参数；未注册模块会被拒绝并返回可用模块清单。\n- SQL 仅允许单条只读 SELECT：不要写分页 LIMIT（系统自动追加），不要写 deleted 条件（已删除数据系统已自动剔除）。\n- 系统表与平台数据（sys_/ai_ 等）不可查询。\n- 执行失败时根据报错修正 SQL 重试（最多 2 次），仍失败如实说明原因。\n- 回答用 Markdown 表格呈现数据，给出来源表名与统计口径说明。'
WHERE agent_code = 'biz_analyst'
  AND deleted = 0
  AND scope = 'tenant'
  AND tool_codes = 'biz_query';

-- ③ data_analyst 更名「数据中台智能分析师」（平台行由 data-service 提供方自动刷新；
--    此处治理仍挂旧名称的租户副本行）
UPDATE ai_agent
SET name = '数据中台智能分析师'
WHERE agent_code = 'data_analyst'
  AND deleted = 0
  AND scope = 'tenant'
  AND name = '数据智能分析师';
