-- mse-target: master
-- mse-per-tenant: true
SET NAMES utf8mb4;
-- =====================================================================
-- 监控中心通知路由种子退役（独立工程 mse-modules/mse-monitor，MySQL，每租户通道，${tenant_id} 注入）
-- 内容：清理 V20261019_01 播种的 (monitor,*) 默认路由及租户自建的 monitor 路由——
--       告警通知 v3.1 恢复独立直发、退出框架通知中枢（通知设置即开关，不经路由解析）
-- 幂等：DELETE 天然幂等，可重复执行
-- =====================================================================

DELETE FROM sys_notify_route WHERE tenant_id = ${tenant_id} AND biz_code = 'monitor';
