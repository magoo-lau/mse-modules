-- mse-target: master
SET NAMES utf8mb4;
-- =====================================================================
-- 智能体「直出图表」开关（ai_agent.chart_direct）：开启后对话运行时在系统提示词
-- 末尾追加「一句话极简结论 + 图表中文列」输出纪律（mse-ai AgentRuntime 注入），
-- 查询结果交由前端数据卡片直出，不再输出长文字解读。
-- 声明智能体的平台行随提供方权威刷新（AiAgentDescriptor.chartDirect，登记器幂等采纳），
-- 租户副本可编辑；本脚本负责存量租户副本的声明默认值对齐。
-- 目标库：业务主库 + 各租户独立库；平台库份见 V20261020_02（结构两处同步）。
-- 幂等：MySQL 无 ADD COLUMN IF NOT EXISTS，
--       用 information_schema 判重 + PREPARE 动态 SQL（迁移器按分号拆句、同一连接逐句执行）。
-- =====================================================================

-- ---------------- 加列：ai_agent.chart_direct ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ai_agent ADD COLUMN chart_direct TINYINT NOT NULL DEFAULT 0 COMMENT ''直出图表模式：0关 1开（一句话极简结论+图表中文列，运行时注入输出纪律；声明字段随提供方刷新平台行，租户副本可编辑）'' AFTER kb_codes',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'chart_direct');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 业务分析师（biz_analyst）默认开启：存量租户副本对齐声明默认值 ----------------
-- 平台行由 BuiltinBizAgentProvider 启动/推送登记按提供方权威刷新（chartDirect=true），无需 SQL；
-- 新字段尚无租户自定义口径，全部租户副本统一置 1（租户此后可在智能体管理关停）。
UPDATE ai_agent
SET chart_direct = 1
WHERE agent_code = 'biz_analyst'
  AND deleted = 0
  AND scope = 'tenant';
