-- mse-target: base
-- mse-base-scope: platform
SET NAMES utf8mb4;
-- =====================================================================
-- 工具登记种子退役·平台库份（与 master 通道 V20261012_01 同款，两库两份维护）：
-- 删除 V20261010_03 写死的 3 行 rpc 引导行（oa_flow 8101 / data_query 8102 /
-- biz_query 8103，平台世界登记行本体）。登记来源全动态化——启动扫描（runIgnore
-- 落 primary=平台库写入 0 行）与提供方推送在应用启动/下一周期重建，租户副本由
-- AiToolService.ensureDeclaredTenantCopies 懒补齐。
-- 幂等：DELETE 按码定位，重复应用无效果。
-- =====================================================================

DELETE FROM ai_tool
WHERE tenant_id = 0
  AND deleted = 0
  AND tool_code IN ('oa_flow', 'data_query', 'biz_query');
