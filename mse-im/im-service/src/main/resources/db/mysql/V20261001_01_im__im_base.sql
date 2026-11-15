-- mse-target: base
-- =====================================================================
-- mse-im 基座接入脚本（独立工程 mse-modules/mse-im，MySQL）
-- 内容：子应用注册（sys_micro_app）+ 页面菜单（sys_menu type=3）+ 权限点（sys_permission）
-- 随 jar 由 starter-bridge 推送 mse-system，经租户库车队重放落共享主库与各独立租户库
-- 幂等：全段 NOT EXISTS 判重
-- 号段：micro_app id=12、菜单 516-521、权限点 4600-4608（登记见 alloc-tables.md）
-- 可见端：子应用双端（side=3）——租户端 516 目录（IM 设置/机器人/工作台配置）+
--       平台端 519 目录（IM 开通）；页面菜单需在「套餐管理」勾选（页面级裁剪），普通用户另需角色授权
-- =====================================================================

-- ---------------- 子应用注册（按 name 判重） ----------------
INSERT INTO sys_micro_app (id, name, title, dev_entry, prod_entry, side, sort, status, remark, create_time, update_time)
SELECT 12, 'im', '即时通讯', '//localhost:4030', '/sub-apps/im/', 3, 12, 0, '独立工程 mse-modules/mse-im（im-ui + im-boot，OpenIM 集成）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_micro_app WHERE name = 'im');

-- ---------------- 租户端页面菜单（type=3；分组目录 path 即路由前缀 /sub/im；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 516 AS id, 0 AS parent_id, '即时通讯' AS name, 1 AS type, 2 AS menu_scope, '/sub/im' AS path, NULL AS micro_name, 'ChatDotRound' AS icon, 24 AS sort
    UNION ALL SELECT 517, 516, 'IM 设置', 3, 2, '/settings', 'im', NULL, 1
    UNION ALL SELECT 518, 516, '机器人',   3, 2, '/bot',      'im', NULL, 2
    UNION ALL SELECT 521, 516, '工作台配置', 3, 2, '/workbench', 'im', 'Grid', 3
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 平台端页面菜单（menu_scope=1 平台端；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 519 AS id, 0 AS parent_id, '即时通讯管理' AS name, 1 AS type, 1 AS menu_scope, '/sub/im' AS path, NULL AS micro_name, 'ChatLineRound' AS icon, 25 AS sort
    UNION ALL SELECT 520, 519, 'IM 开通', 3, 1, '/provision', 'im', NULL, 1
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 权限点（挂靠页面菜单：参与授权界面分组与套餐裁剪） ----------------
INSERT INTO sys_permission (id, menu_id, module, name, code, sort, status, create_time, update_time)
SELECT t.id, t.menu_id, t.module, t.name, t.code, t.sort, 0, NOW(), NOW()
FROM (
    SELECT 4600 AS id, 517 AS menu_id, 'im' AS module, '实例配置查询' AS name, 'im:instance:query' AS code, 1 AS sort
    UNION ALL SELECT 4601, 517, 'im', '实例配置保存', 'im:instance:save', 2
    UNION ALL SELECT 4602, 517, 'im', '实例连通测试', 'im:instance:test', 3
    UNION ALL SELECT 4603, 518, 'im', '机器人查询',   'im:bot:query',     4
    UNION ALL SELECT 4604, 518, 'im', '机器人编辑',   'im:bot:save',      5
    UNION ALL SELECT 4605, 518, 'im', '机器人删除',   'im:bot:delete',    6
    UNION ALL SELECT 4606, 520, 'im', 'IM 开通管理',  'im:provision:manage', 7
    UNION ALL SELECT 4607, 521, 'im', '工作台配置查询', 'im:workbench:query', 8
    UNION ALL SELECT 4608, 521, 'im', '工作台配置保存', 'im:workbench:save', 9
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id = t.id);
