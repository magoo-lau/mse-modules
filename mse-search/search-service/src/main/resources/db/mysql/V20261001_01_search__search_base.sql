-- mse-target: base
-- =====================================================================
-- 搜索引擎服务基座接入脚本（独立工程 mse-modules/mse-search，MySQL）
-- 目标库：基座库（sys_* 全局表所在库；分库部署时对基座库执行）
-- 内容：子应用注册（sys_micro_app）+ 页面菜单（sys_menu type=3）+ 权限点（sys_permission）
-- 配套：search_init.sql（对业务库执行：业务表）；search_platform.sql（平台库业务表副本）
-- 幂等：全段 NOT EXISTS 判重，可重复执行
-- 执行：自动——随业务 jar 推送 mse-system 应用（mse_<biz>_schema_history 分册账本），无需手工执行
-- 号段：micro_app id=9、菜单 480-484、486、487、权限点 4201-4211（登记见 alloc-tables.md）
-- 可见端：双端（side=3）；管理页面全挂租户端（menu_scope=2，租户自管搜索源/任务/索引），
--       「搜索配置」租户端；搜索框本体在基座首页卡片/顶栏，不占菜单；搜索 API 登录即可
-- =====================================================================

-- ---------------- 子应用注册（按 name 判重） ----------------
INSERT INTO sys_micro_app (id, name, title, dev_entry, prod_entry, side, sort, status, remark, create_time, update_time)
SELECT 9, 'search', '搜索引擎', '//localhost:4000', '/sub-apps/search/', 3, 9, 0, '独立工程 mse-modules/mse-search（search-ui + search-boot，双端）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_micro_app WHERE name = 'search');

-- ---------------- 页面菜单（type=3；分组目录 scope=3 双端；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 480 AS id, 0 AS parent_id, '搜索引擎' AS name, 1 AS type, 3 AS menu_scope, '/sub/search' AS path, NULL AS micro_name, 'Search' AS icon, 27 AS sort
    UNION ALL SELECT 481, 480, '源管理',   3, 2, '/source',   'search', NULL, 1
    UNION ALL SELECT 482, 480, '任务中心', 3, 2, '/task',     'search', NULL, 2
    UNION ALL SELECT 483, 480, '热词统计', 3, 2, '/hot',      'search', NULL, 3
    UNION ALL SELECT 484, 480, '搜索测试', 3, 2, '/playground', 'search', NULL, 4
    UNION ALL SELECT 486, 480, '索引管理', 3, 2, '/index',    'search', NULL, 5
    UNION ALL SELECT 487, 480, '搜索配置', 3, 2, '/tenant-field', 'search', NULL, 6
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 权限点（挂靠页面菜单：参与授权界面分组与套餐裁剪） ----------------
INSERT INTO sys_permission (id, menu_id, module, name, code, sort, status, create_time, update_time)
SELECT t.id, t.menu_id, t.module, t.name, t.code, t.sort, 0, NOW(), NOW()
FROM (
    SELECT 4201 AS id, 481 AS menu_id, 'search' AS module, '源查询' AS name, 'search:source:list' AS code, 1 AS sort
    UNION ALL SELECT 4202, 481, 'search', '源编辑（调参/启停/手工登记）', 'search:source:save', 2
    UNION ALL SELECT 4203, 482, 'search', '任务查询',   'search:task:list', 3
    UNION ALL SELECT 4204, 482, 'search', '任务触发（重建/优化/扫描）', 'search:task:save', 4
    UNION ALL SELECT 4205, 483, 'search', '热词统计查询', 'search:stats:list', 5
    UNION ALL SELECT 4206, 484, 'search', '搜索调试（推文档/过滤对比）', 'search:debug:use', 6
    UNION ALL SELECT 4207, 486, 'search', '索引查询/数据浏览', 'search:index:list', 7
    UNION ALL SELECT 4208, 486, 'search', '索引删除/清空', 'search:index:delete', 8
    UNION ALL SELECT 4209, 481, 'search', '源字段配置', 'search:source:field', 9
    UNION ALL SELECT 4210, 487, 'search', '搜索配置查询（租户）', 'search:tenantconfig:list', 10
    UNION ALL SELECT 4211, 487, 'search', '搜索配置保存（租户）', 'search:tenantconfig:save', 11
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id = t.id);
