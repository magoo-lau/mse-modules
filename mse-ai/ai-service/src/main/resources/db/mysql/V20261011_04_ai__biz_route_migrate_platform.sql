-- mse-target: base
-- mse-base-scope: platform
SET NAMES utf8mb4;
-- =====================================================================
-- biz_query 模块化路由迁移·平台库份（与 master 通道 V20261011_03 同款，两库两份维护）：
-- 平台库 ai_tool 静态基线行 biz_query（8103）归 mse-ai 自持。
-- 智能体平台行无需处理：平台库无 biz_analyst/data_analyst 静态种子（业务智能体种子已退役，
-- 改由提供方声明登记自动治理），工具/智能体动态登记写业务主库 0 行后随本 UPDATE 归一。
-- 幂等：条件 UPDATE，重复应用无效果。
-- =====================================================================

UPDATE ai_tool
SET source = 'builtin',
    rpc_group = NULL
WHERE tool_code = 'biz_query'
  AND deleted = 0
  AND (source <> 'builtin' OR rpc_group IS NOT NULL);
