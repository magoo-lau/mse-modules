-- mse-target: master
SET NAMES utf8mb4;
-- =====================================================================
-- 工具登记种子退役：删除 V20261010_01 写死的 3 行 rpc 引导行（oa_flow 8101 /
-- data_query 8102 / biz_query 8103，tenant_id=0 平台行），登记来源全动态化——
-- builtin/local 由 LocalToolAutoRegistrar 启动扫描登记、rpc 由提供方 ai-api
-- 推送器周期重推，应用启动即按提供方 describe() 重建且 source 归位正确
-- （单体聚合=local、微服务=rpc），工具中心与「绑定工具」下拉清单不再依赖
-- 静态种子；既有租户世界副本由 AiToolService.ensureDeclaredTenantCopies
-- 在读取入口懒补齐/内容刷新（建租户播种只覆盖建租户一刻）。
-- 影响面：既有环境本三行的手工启停/备注随之重置（重建后回默认启用）；
-- 微服务形态 rpc 工具重建前有最长一个推送周期的登记空窗；租户副本行不受
-- 本脚本影响。
-- 幂等：DELETE 按码定位，重复应用无效果。
-- =====================================================================

DELETE FROM ai_tool
WHERE tenant_id = 0
  AND deleted = 0
  AND tool_code IN ('oa_flow', 'data_query', 'biz_query');
