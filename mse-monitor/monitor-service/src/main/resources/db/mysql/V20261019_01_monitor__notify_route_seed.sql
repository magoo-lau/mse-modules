-- mse-target: master
-- mse-per-tenant: true
SET NAMES utf8mb4;
-- =====================================================================
-- 监控中心通知路由种子（独立工程 mse-modules/mse-monitor，MySQL，每租户通道，${tenant_id} 注入）
-- 内容：监控告警默认路由 (monitor,*) → inbox,webhook——告警投递已迁框架通知中枢
--       （2026-10-19 v3，自有站内信直调/Webhook 直发退役），本种子保证升级行为等价
--       （迁移前 mon_setting 开关 inboxEnabled/webhookEnabled 均默认可用组合）；
--       租户可在基座系统管理「通知渠道设置」调整（渠道参数仍在监控中心「通知设置」维护）
-- 幂等：按 (tenant_id, biz_code) 判重，重复应用无害
-- 说明：平台域默认路由在 service-system V20261019_05（平台库种子 tenant_id=0）
-- =====================================================================

INSERT INTO sys_notify_route (id, tenant_id, biz_code, msg_type, channels, status, remark, create_time, update_time)
SELECT ${tenant_id} % 100000000 * 100 + 61, ${tenant_id}, 'monitor', '*', 'inbox,webhook', 0,
       '监控告警默认路由（种子，可在系统管理-通知渠道设置调整）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_notify_route r
                  WHERE r.tenant_id = ${tenant_id} AND r.biz_code = 'monitor' AND r.deleted = 0);
