-- mse-target: base
-- =====================================================================
-- 监控中心基座接入脚本（独立工程 mse-modules/mse-monitor，MySQL）
-- 目标库：基座库（sys_* 全局表所在库；分库部署时对基座库执行）
-- 内容：子应用注册（sys_micro_app）+ 页面菜单（sys_menu type=3）+ 权限点（sys_permission）
-- 配套：monitor_init.sql（对业务库执行：业务表）；monitor_platform.sql（平台库业务表副本）
-- 幂等：全段 NOT EXISTS 判重，可重复执行
-- 执行：自动——随业务 jar 推送 mse-system 应用（mse_<biz>_schema_history 分册账本），无需手工执行
-- 号段：micro_app id=10、菜单 490-494、权限点 4301-4312（495/4311 已随缓存页合并释放；登记见 alloc-tables.md）
-- 可见端：双端（side=3 / menu_scope=3）——平台端看平台自身监控（tenant_id=0 行），
--       租户端看本租户监控（数据按会话租户路由/过滤）；缓存页合并为单一「缓存监控」，
--       删键按钮由 v-permission('monitor:cache:delete') 控制显隐
-- =====================================================================

-- ---------------- 子应用注册（按 name 判重） ----------------
INSERT INTO sys_micro_app (id, name, title, dev_entry, prod_entry, side, sort, status, remark, create_time, update_time)
SELECT 10, 'monitor', '监控中心', '//localhost:4010', '/sub-apps/monitor/', 3, 10, 0, '独立工程 mse-modules/mse-monitor（monitor-ui + monitor-boot，平台/租户双端）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_micro_app WHERE name = 'monitor');

-- ---------------- 页面菜单（type=3；双端；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 490 AS id, 0 AS parent_id, '监控中心' AS name, 1 AS type, 3 AS menu_scope, '/sub/monitor' AS path, NULL AS micro_name, 'Monitor' AS icon, 28 AS sort
    UNION ALL SELECT 491, 490, '监控总览', 3, 3, '/dashboard', 'monitor', NULL, 1
    UNION ALL SELECT 492, 490, '监控目标', 3, 3, '/targets',   'monitor', NULL, 2
    UNION ALL SELECT 493, 490, '告警中心', 3, 3, '/alerts',    'monitor', NULL, 3
    UNION ALL SELECT 494, 490, '缓存监控', 3, 3, '/redis',     'monitor', NULL, 4
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 权限点（挂靠页面菜单：参与授权界面分组与套餐裁剪；双端共享） ----------------
INSERT INTO sys_permission (id, menu_id, module, name, code, sort, status, create_time, update_time)
SELECT t.id, t.menu_id, t.module, t.name, t.code, t.sort, 0, NOW(), NOW()
FROM (
    SELECT 4301 AS id, 491 AS menu_id, 'monitor' AS module, '总览查询' AS name, 'monitor:dashboard:query' AS code, 1 AS sort
    UNION ALL SELECT 4302, 492, 'monitor', '目标查询', 'monitor:target:query', 2
    UNION ALL SELECT 4303, 492, 'monitor', '目标编辑', 'monitor:target:save', 3
    UNION ALL SELECT 4304, 492, 'monitor', '目标删除', 'monitor:target:delete', 4
    UNION ALL SELECT 4305, 492, 'monitor', '立即检测', 'monitor:target:probe', 5
    UNION ALL SELECT 4306, 493, 'monitor', '告警查询', 'monitor:alert:query', 6
    UNION ALL SELECT 4307, 493, 'monitor', '告警处理', 'monitor:alert:handle', 7
    UNION ALL SELECT 4308, 493, 'monitor', '通知设置查看', 'monitor:notify:query', 8
    UNION ALL SELECT 4309, 493, 'monitor', '通知设置维护', 'monitor:notify:save', 9
    UNION ALL SELECT 4310, 494, 'monitor', '缓存查询', 'monitor:redis:query', 10
    UNION ALL SELECT 4312, 494, 'monitor', '缓存键删除', 'monitor:cache:delete', 12
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id = t.id);
