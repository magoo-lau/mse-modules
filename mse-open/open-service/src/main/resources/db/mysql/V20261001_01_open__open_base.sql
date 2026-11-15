-- mse-target: base
SET NAMES utf8mb4;
-- =====================================================================
-- 开放能力基座接入脚本（独立工程 mse-modules/mse-open，MySQL）
-- 目标库：基座库（sys_* 全局表所在库；分库部署时对基座库执行）
-- 内容：子应用注册（sys_micro_app）+ 页面菜单（sys_menu type=3）+ 权限点（sys_permission）
--       + 操作日志模块翻译字典（sys_oper_module）
-- 配套：open_init.sql（对业务库执行：业务表 + API 清单种子）；tenant_seed.sql（每租户目录播种）
-- 幂等：全段 NOT EXISTS 判重，可重复执行
-- 执行：自动——随业务 jar 推送 mse-system 应用（mse_<biz>_schema_history 分册账本），无需手工执行
-- 号段：micro_app id=5、菜单 450-455、权限点 7xxx、字典数据 41（登记见 zcode-plugin/plugins/mse-dev/references/alloc-tables.md）
-- 可见端：租户拥有（side=1 / menu_scope=2，租户自管自己的开放应用/API/凭证）；
--       应用绑定表单中的「归属租户」由租户上下文自动填充
-- =====================================================================

-- ---------------- 子应用注册（按 name 判重） ----------------
INSERT INTO sys_micro_app (id, name, title, dev_entry, prod_entry, side, sort, status, remark, create_time, update_time)
SELECT 5, 'open', '开放能力', '//localhost:3600', '/sub-apps/open/', 1, 5, 0, '独立工程 mse-modules/mse-open（open-ui + open-boot）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_micro_app WHERE name = 'open');

-- ---------------- 页面菜单（type=3，租户端；分组目录 path 即路由前缀 /sub/open；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 450 AS id, 0 AS parent_id, '开放能力' AS name, 1 AS type, 2 AS menu_scope, '/sub/open' AS path, NULL AS micro_name, 'Box' AS icon, 30 AS sort
    UNION ALL SELECT 451, 450, '应用管理', 3, 2, '/apps',  'open', NULL, 1
    UNION ALL SELECT 452, 450, '调用日志', 3, 2, '/logs',  'open', NULL, 2
    UNION ALL SELECT 453, 450, '身份绑定', 3, 2, '/binds', 'open', NULL, 3
    UNION ALL SELECT 454, 450, '接入指南', 3, 2, '/guide', 'open', NULL, 4
    UNION ALL SELECT 455, 450, 'API 目录', 3, 2, '/apis', 'open', NULL, 5
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 权限点（挂靠页面菜单：参与授权界面分组与套餐裁剪） ----------------
INSERT INTO sys_permission (id, menu_id, module, name, code, sort, status, create_time, update_time)
SELECT t.id, t.menu_id, t.module, t.name, t.code, t.sort, 0, NOW(), NOW()
FROM (
    SELECT 7001 AS id, 451 AS menu_id, 'open' AS module, '应用查询' AS name, 'open:app:list' AS code, 1 AS sort
    UNION ALL SELECT 7002, 451, 'open', '应用详情',     'open:app:query',         2
    UNION ALL SELECT 7003, 451, 'open', '应用新增',     'open:app:create',        3
    UNION ALL SELECT 7004, 451, 'open', '应用修改',     'open:app:update',        4
    UNION ALL SELECT 7005, 451, 'open', '应用启停',     'open:app:change-status', 5
    UNION ALL SELECT 7006, 451, 'open', '重置密钥',     'open:app:reset-secret',  6
    UNION ALL SELECT 7007, 451, 'open', '应用删除',     'open:app:delete',        7
    UNION ALL SELECT 7008, 451, 'open', 'API 授权',     'open:app:grant',         8
    UNION ALL SELECT 7009, 451, 'open', 'API 清单查询', 'open:api:list',          9
    UNION ALL SELECT 7010, 452, 'open', '调用日志查询', 'open:log:list',          10
    UNION ALL SELECT 7011, 453, 'open', '绑定关系查询', 'open:bind:list',         11
    UNION ALL SELECT 7012, 455, 'open', 'API 目录启停', 'open:api:status',       12
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id = t.id);

-- ---------------- 操作日志「系统模块」翻译字典（字典类型行由基座基线维护） ----------------
INSERT INTO sys_dict_data (id, tenant_id, dict_type, dict_label, dict_value, dict_sort, list_class, is_default, status, remark, create_time, update_time)
SELECT t.id, t.tenant_id, t.dict_type, t.dict_label, t.dict_value, t.dict_sort, t.list_class, t.is_default, t.status, t.remark, NOW(), NOW()
FROM (
    SELECT 41 AS id, 0 AS tenant_id, 'sys_oper_module' AS dict_type, '开放应用' AS dict_label, 'open:app' AS dict_value, 20 AS dict_sort, NULL AS list_class, 0 AS is_default, 0 AS status, NULL AS remark FROM DUAL
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data x WHERE x.id = t.id);
