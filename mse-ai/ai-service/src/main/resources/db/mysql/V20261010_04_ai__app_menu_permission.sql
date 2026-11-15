-- mse-target: base
-- =====================================================================
-- 本文件为 ai 模块最终态基线，由 V20261001_01~V20261005_01 合并而来，中间演进（加列/种子退役）已内联为最终形态
-- AI 服务基座接入脚本（独立工程 mse-modules/mse-ai，MySQL）
-- 目标库：基座库（sys_* 全局表所在库；分库部署时对基座库执行）
-- 内容：子应用注册（sys_micro_app）+ 页面菜单（sys_menu type=3）+ 权限点（sys_permission）
--       最终集（原 V20261001_01 + V20261003_01 合并，ai:chat:trace 8024 已并入权限点清单）
-- 配套：业务表/种子见 V20261010_01（master）、每租户播种见 V20261010_02、平台库份见 V20261010_03
-- 幂等：全段 NOT EXISTS 判重，可重复执行
-- 执行：自动——随业务 jar 推送 mse-system 应用（mse_<biz>_schema_history 分册账本），无需手工执行
-- 号段：micro_app id=7、菜单 460-468、权限点 8001-8024（469/8005/8022/8023 已退役；登记见 alloc-tables.md）
-- 可见端：租户拥有（side=3 子应用双端兜底，页面 menu_scope=2 租户端）——智能体/模型/MCP/API Key/
--       用量/工具中心全租户自管；页面菜单需在「套餐管理」勾选（页面级裁剪），普通用户另需角色授权
-- =====================================================================

-- ---------------- 子应用注册（按 name 判重） ----------------
INSERT INTO sys_micro_app (id, name, title, dev_entry, prod_entry, side, sort, status, remark, create_time, update_time)
SELECT 7, 'ai', 'AI 服务', '//localhost:3800', '/sub-apps/ai/', 3, 7, 0, '独立工程 mse-modules/mse-ai（ai-ui + ai-boot）', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_micro_app WHERE name = 'ai');

-- ---------------- 页面菜单（type=3；全租户端；按 id 判重） ----------------
INSERT INTO sys_menu (id, parent_id, name, type, menu_scope, path, micro_name, component, icon, sort, visible, status, create_time, update_time)
SELECT t.id, t.parent_id, t.name, t.type, t.menu_scope, t.path, t.micro_name, NULL, t.icon, t.sort, 0, 0, NOW(), NOW()
FROM (
    SELECT 460 AS id, 0 AS parent_id, 'AI 服务' AS name, 1 AS type, 2 AS menu_scope, '/sub/ai' AS path, NULL AS micro_name, 'MagicStick' AS icon, 25 AS sort
    UNION ALL SELECT 461, 460, '对话工作台', 3, 2, '/chat',            'ai', NULL, 1
    UNION ALL SELECT 462, 460, '智能体管理', 3, 2, '/agent',           'ai', NULL, 2
    UNION ALL SELECT 463, 460, '知识库',     3, 2, '/kb',              'ai', NULL, 3
    UNION ALL SELECT 465, 460, '用量统计',   3, 2, '/usage',           'ai', NULL, 5
    UNION ALL SELECT 466, 460, '模型管理',   3, 2, '/platform/model',  'ai', NULL, 6
    UNION ALL SELECT 464, 460, '工具中心',   3, 2, '/tool',            'ai', NULL, 7
    UNION ALL SELECT 467, 460, '开放能力',   3, 2, '/platform/open',   'ai', NULL, 8
    UNION ALL SELECT 468, 460, '用量统计',   3, 2, '/platform/usage',  'ai', NULL, 9
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id = t.id);

-- ---------------- 权限点（挂靠页面菜单：参与授权界面分组与套餐裁剪；全租户端） ----------------
-- 对话（含工具调用轨迹）、智能体（自管）、知识库、工具中心（系统工具只读 + 自建 http/dify + 管理）、
-- 模型、MCP/API Key、用量
INSERT INTO sys_permission (id, menu_id, module, name, code, sort, status, create_time, update_time)
SELECT t.id, t.menu_id, t.module, t.name, t.code, t.sort, 0, NOW(), NOW()
FROM (
    SELECT 8001 AS id, 466 AS menu_id, 'ai' AS module, '模型查询' AS name, 'ai:model:list' AS code, 1 AS sort
    UNION ALL SELECT 8002, 466, 'ai', '模型编辑',     'ai:model:save',        2
    UNION ALL SELECT 8003, 466, 'ai', '模型删除',     'ai:model:delete',      3
    UNION ALL SELECT 8004, 466, 'ai', '模型连通性测试', 'ai:model:test',      4
    UNION ALL SELECT 8006, 462, 'ai', '智能体查询',   'ai:agent:list',        6
    UNION ALL SELECT 8007, 462, 'ai', '智能体编辑',   'ai:agent:save',        7
    UNION ALL SELECT 8008, 462, 'ai', '智能体删除',   'ai:agent:delete',      8
    UNION ALL SELECT 8009, 461, 'ai', 'AI 对话',      'ai:chat:use',          9
    UNION ALL SELECT 8010, 463, 'ai', '知识库查询',   'ai:kb:list',           10
    UNION ALL SELECT 8011, 463, 'ai', '知识库编辑',   'ai:kb:save',           11
    UNION ALL SELECT 8012, 463, 'ai', '文档上传',     'ai:kb:upload',         12
    UNION ALL SELECT 8013, 463, 'ai', '知识库检索',   'ai:kb:search',         13
    UNION ALL SELECT 8014, 464, 'ai', '工具查询',     'ai:tool:list',         14
    UNION ALL SELECT 8015, 465, 'ai', '用量查询',     'ai:usage:list',        15
    UNION ALL SELECT 8016, 467, 'ai', 'MCP 查询',     'ai:mcp:list',          16
    UNION ALL SELECT 8017, 467, 'ai', 'MCP 编辑',     'ai:mcp:save',          17
    UNION ALL SELECT 8018, 467, 'ai', 'API Key 查询', 'ai:apikey:list',       18
    UNION ALL SELECT 8019, 467, 'ai', 'API Key 签发', 'ai:apikey:create',     19
    UNION ALL SELECT 8020, 468, 'ai', '全局用量查询', 'ai:usage:platform',    20
    UNION ALL SELECT 8021, 464, 'ai', '工具管理',     'ai:tool:manage',       21
    UNION ALL SELECT 8024, 461, 'ai', '对话工具轨迹', 'ai:chat:trace',        22
) t
WHERE NOT EXISTS (SELECT 1 FROM sys_permission p WHERE p.id = t.id);
