-- mse-target: base
-- =====================================================================
-- 数据中台基座接入脚本（独立工程 mse-modules/mse-data，MySQL）
-- 目标库：基座库（sys_* 全局表所在库，即 mse_platform；分库部署时对基座库执行）
-- 内容：子应用注册（sys_micro_app）+ 页面菜单（sys_menu type=3）+ 权限点（sys_permission）
-- 配套：data_init.sql（对业务库执行：业务表 + 示例数据）；单库演示两脚本都对 mse_platform 执行
-- 幂等：全段 NOT EXISTS 判重，可重复执行
-- 执行：自动——随业务 jar 推送 mse-system 应用（mse_<biz>_schema_history 分册账本），无需手工执行
-- 版本化：V{yyyyMMdd}_{NN}_<biz>__<描述>.sql（biz 段路由分册账本 mse_<biz>_schema_history，同 biz 内字典序即时间序）；
--           已应用脚本不再修改（改动报 checksum 冲突），变更一律新增版本文件
-- 号段：micro_app id=6、菜单 440-447、权限点 9501+（登记见 zcode-plugin/plugins/mse-dev/references/alloc-tables.md）
-- 租户端可见性：页面菜单需在「套餐管理」勾选（页面级裁剪），普通用户另需角色授权
-- =====================================================================

-- ---------------- 子应用注册（按 name 判重） ----------------
INSERT INTO sys_micro_app (id, name, title, dev_entry, prod_entry, side, sort, status, remark, create_time, update_time)
SELECT 6, 'data', '数据中台', '//localhost:3700', '/sub-apps/data/', 1, 6, 0, '独立工程 mse-modules/mse-data（data-ui + data-boot）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_micro_app WHERE name = 'data');

-- ---------------- 页面菜单（type=3；分组目录 path 即路由前缀 /sub/data；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 440 AS id, 0 AS parent_id, '数据中台' AS name, 1 AS type, 2 AS menu_scope, '/sub/data' AS path, NULL AS micro_name, 'Box' AS icon, 24 AS sort
    UNION ALL SELECT 441, 440, '数据概览',   3, 2, '/overview',   'data', NULL, 1
    UNION ALL SELECT 442, 440, '数据源管理', 3, 2, '/datasource', 'data', NULL, 2
    UNION ALL SELECT 443, 440, '数据集成',   3, 2, '/sync',       'data', NULL, 3
    UNION ALL SELECT 444, 440, '数据地图',   3, 2, '/datamap',    'data', NULL, 4
    UNION ALL SELECT 445, 440, '数据服务',   3, 2, '/service',    'data', NULL, 5
    UNION ALL SELECT 446, 440, '质量监控',   3, 2, '/quality',    'data', NULL, 6
    UNION ALL SELECT 447, 440, '查询白名单', 3, 2, '/allowlist',  'data', NULL, 7
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 权限点（挂靠页面菜单：参与授权界面分组与套餐裁剪，替代早期“通用权限”形态） ----------------
INSERT INTO sys_permission (id, menu_id, module, name, code, sort, status, create_time, update_time)
SELECT t.id, t.menu_id, t.module, t.name, t.code, t.sort, 0, NOW(), NOW()
FROM (
    SELECT 9501 AS id, 442 AS menu_id, 'data' AS module, '数据源查询' AS name, 'data:source:list' AS code, 1 AS sort
    UNION ALL SELECT 9502, 442, 'data', '数据源编辑',     'data:source:save',      2
    UNION ALL SELECT 9503, 442, 'data', '数据源删除',     'data:source:delete',    3
    UNION ALL SELECT 9504, 443, 'data', '同步任务查询',   'data:sync:list',        4
    UNION ALL SELECT 9505, 443, 'data', '同步任务编辑',   'data:sync:save',        5
    UNION ALL SELECT 9506, 443, 'data', '同步任务删除',   'data:sync:delete',      6
    UNION ALL SELECT 9507, 443, 'data', '同步任务执行',   'data:sync:run',         7
    UNION ALL SELECT 9508, 444, 'data', '元数据查询',     'data:meta:list',        8
    UNION ALL SELECT 9509, 444, 'data', '元数据采集',     'data:meta:collect',     9
    UNION ALL SELECT 9510, 445, 'data', 'API 查询',       'data:service:list',     10
    UNION ALL SELECT 9511, 445, 'data', 'API 编辑',       'data:service:save',     11
    UNION ALL SELECT 9512, 445, 'data', 'API 删除',       'data:service:delete',   12
    UNION ALL SELECT 9513, 445, 'data', '调用方应用管理', 'data:service:app',      13
    UNION ALL SELECT 9514, 441, 'data', '概览查询',       'data:overview:list',    14
    UNION ALL SELECT 9515, 446, 'data', '质量规则查询',   'data:quality:list',     15
    UNION ALL SELECT 9516, 446, 'data', '质量规则编辑',   'data:quality:save',     16
    UNION ALL SELECT 9517, 446, 'data', '质量规则删除',   'data:quality:delete',   17
    UNION ALL SELECT 9518, 446, 'data', '质量规则执行',   'data:quality:run',      18
    UNION ALL SELECT 9519, 445, 'data', 'API 审批',       'data:service:approve',  19
    UNION ALL SELECT 9520, 444, 'data', '数据导出',       'data:meta:export',      20
    UNION ALL SELECT 9521, 447, 'data', '查询白名单查询', 'data:allow:list',       21
    UNION ALL SELECT 9522, 447, 'data', '查询白名单编辑', 'data:allow:save',       22
    UNION ALL SELECT 9523, 447, 'data', '查询白名单删除', 'data:allow:delete',     23
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id = t.id);

-- ---------------- 升级兼容：早期安装的权限点未挂菜单（menu_id=NULL，按通用权限登录即授予），此处幂等补挂 ----------------
UPDATE sys_permission SET menu_id = 442, update_time = NOW() WHERE id = 9501 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 442, update_time = NOW() WHERE id = 9502 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 442, update_time = NOW() WHERE id = 9503 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 443, update_time = NOW() WHERE id = 9504 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 443, update_time = NOW() WHERE id = 9505 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 443, update_time = NOW() WHERE id = 9506 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 443, update_time = NOW() WHERE id = 9507 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 444, update_time = NOW() WHERE id = 9508 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 444, update_time = NOW() WHERE id = 9509 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 445, update_time = NOW() WHERE id = 9510 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 445, update_time = NOW() WHERE id = 9511 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 445, update_time = NOW() WHERE id = 9512 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 445, update_time = NOW() WHERE id = 9513 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 441, update_time = NOW() WHERE id = 9514 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 446, update_time = NOW() WHERE id = 9515 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 446, update_time = NOW() WHERE id = 9516 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 446, update_time = NOW() WHERE id = 9517 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 446, update_time = NOW() WHERE id = 9518 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 445, update_time = NOW() WHERE id = 9519 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 444, update_time = NOW() WHERE id = 9520 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 447, update_time = NOW() WHERE id = 9521 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 447, update_time = NOW() WHERE id = 9522 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 447, update_time = NOW() WHERE id = 9523 AND menu_id IS NULL;
