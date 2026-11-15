-- mse-target: base
-- mse-base-scope: platform
SET NAMES utf8mb4;
-- =====================================================================
-- 智能体「直出图表」开关·平台库份（与 master 通道 V20261020_01 同款加列，两库两份维护）：
-- ai_agent 加列 chart_direct（tinyint NOT NULL DEFAULT 0）。
-- 平台行取值无需 SQL：biz_analyst 平台行由 mse-ai 内置提供方 BuiltinBizAgentProvider
-- （chartDirect=true）启动/推送登记按提供方权威自动刷新，其余智能体保持默认关。
-- 幂等：information_schema 判重 + PREPARE 动态 SQL（迁移器按分号拆句、同一连接逐句执行）。
-- =====================================================================

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ai_agent ADD COLUMN chart_direct TINYINT NOT NULL DEFAULT 0 COMMENT ''直出图表模式：0关 1开（一句话极简结论+图表中文列，运行时注入输出纪律；声明字段随提供方刷新平台行，租户副本可编辑）'' AFTER kb_codes',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'chart_direct');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
