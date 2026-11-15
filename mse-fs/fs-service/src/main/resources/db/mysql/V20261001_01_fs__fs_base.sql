-- mse-target: base
-- =====================================================================
-- 文件存储服务基座接入脚本（独立工程 mse-modules/mse-fs，MySQL）
-- 目标库：基座库（sys_* 全局表所在库，即 mse_platform；分库部署时对基座库执行）
-- 内容：子应用注册（sys_micro_app）+ 页面菜单（sys_menu type=3）+ 权限点（sys_permission）
-- 配套：fs_init.sql（对业务库执行：业务表）；单库演示两脚本都对 mse_platform 执行
-- 幂等：全段 NOT EXISTS 判重，可重复执行
-- 执行：自动——随业务 jar 推送 mse-system 应用（mse_<biz>_schema_history 分册账本），无需手工执行
-- 版本化：V{yyyyMMdd}_{NN}_<biz>__<描述>.sql（biz 段路由分册账本 mse_<biz>_schema_history，同 biz 内字典序即时间序）；
--           已应用脚本不再修改（改动报 checksum 冲突），变更一律新增版本文件
-- 号段：micro_app id=8、菜单 470-471、权限点 4101-4105（登记见 zcode-plugin/plugins/mse-dev/references/alloc-tables.md）
-- 租户端可见性：页面菜单需在「套餐管理」勾选（页面级裁剪），普通用户另需角色授权
-- =====================================================================

-- ---------------- 子应用注册（按 name 判重） ----------------
INSERT INTO sys_micro_app (id, name, title, dev_entry, prod_entry, side, sort, status, remark, create_time, update_time)
SELECT 8, 'fs', '文件存储', '//localhost:3900', '/sub-apps/fs/', 1, 8, 0, '独立工程 mse-modules/mse-fs（fs-ui + fs-boot）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_micro_app WHERE name = 'fs');

-- ---------------- 页面菜单（type=3；分组目录 path 即路由前缀 /sub/fs；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 470 AS id, 0 AS parent_id, '文件存储' AS name, 1 AS type, 2 AS menu_scope, '/sub/fs' AS path, NULL AS micro_name, 'FolderOpened' AS icon, 26 AS sort
    UNION ALL SELECT 471, 470, '文件管理', 3, 2, '/file', 'fs', NULL, 1
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 权限点（挂靠页面菜单：参与授权界面分组与套餐裁剪，替代早期“通用权限”形态） ----------------
INSERT INTO sys_permission (id, menu_id, module, name, code, sort, status, create_time, update_time)
SELECT t.id, t.menu_id, t.module, t.name, t.code, t.sort, 0, NOW(), NOW()
FROM (
    SELECT 4101 AS id, 471 AS menu_id, 'fs' AS module, '文件查询' AS name, 'fs:file:list' AS code, 1 AS sort
    UNION ALL SELECT 4102, 471, 'fs', '文件上传', 'fs:file:upload',   2
    UNION ALL SELECT 4103, 471, 'fs', '文件下载', 'fs:file:download', 3
    UNION ALL SELECT 4104, 471, 'fs', '文件删除', 'fs:file:delete',   4
    UNION ALL SELECT 4105, 471, 'fs', '存储迁移', 'fs:storage:migrate', 5
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id = t.id);

-- ---------------- 升级兼容：早期安装的权限点未挂菜单（menu_id=NULL，按通用权限登录即授予），此处幂等补挂 ----------------
UPDATE sys_permission SET menu_id = 471, update_time = NOW() WHERE id = 4101 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 471, update_time = NOW() WHERE id = 4102 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 471, update_time = NOW() WHERE id = 4103 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 471, update_time = NOW() WHERE id = 4104 AND menu_id IS NULL;
UPDATE sys_permission SET menu_id = 471, update_time = NOW() WHERE id = 4105 AND menu_id IS NULL;
