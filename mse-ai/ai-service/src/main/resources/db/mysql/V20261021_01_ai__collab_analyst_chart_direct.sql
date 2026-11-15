-- mse-target: master
SET NAMES utf8mb4;
-- =====================================================================
-- 协同服务分析师（oa_collab_analyst）默认开启直出图表：声明侧 OaAiAgentProvider 已
-- setChartDirect(true)，平台行随启动/推送登记按提供方权威自动刷新（无需 SQL）；
-- 本脚本把存量租户副本对齐声明默认值——新字段尚无租户自定义口径，全部副本统一置 1
-- （租户此后可在智能体管理按副本关停）。
-- 幂等：条件 UPDATE，重复应用无效果（chart_direct 列由 V20261020_01 先行创建，账本序保证）。
-- =====================================================================

UPDATE ai_agent
SET chart_direct = 1
WHERE agent_code = 'oa_collab_analyst'
  AND deleted = 0
  AND scope = 'tenant';
