-- mse-target: master
SET NAMES utf8mb4;
-- =====================================================================
-- 开放能力初始化脚本·最终态基线（独立工程 mse-modules/mse-open，MySQL）
-- 目标库：业务主库 + 各租户独立库（随服务启动/懒初始化按账本应用）
-- 内容：open_* 业务表（结构最终态）+ 开放 API 清单种子
-- 合并说明：由 V20261001_02_open__open_init.sql 与 V20261001_04_open__app_key_widen.sql
--       合并而成——open_app.app_key 直接按最终形态 VARCHAR(64) 建列（免 ALTER，重复执行无害），
--       凭证内嵌世界段 mse-{tenantId}-{hex}（雪花租户号 19 位时总长 40 字符），
--       唯一键 uk_app_key(app_key, deleted) 保留
-- 配套：open_base.sql（基座通道：子应用注册 + 页面菜单 + 权限点）；
--       tenant_seed.sql（per-tenant 通道：平台目录行按租户播种副本 + 授权引用改写）
-- 结构要点：open_api 目录真租户化——唯一键 uk_tenant_code(tenant_id, code, deleted)，code 世界内唯一，
--       平台世界行（tenant_id=0）与各租户副本并存；open_app 带 domain_whitelist 域名白名单
--
-- 表归属：
--   全局表（平台维护，已由 open-service 注册进租户插件白名单）：
--     open_app / open_api / open_app_api / open_api_log
--   租户表（基座租户插件自动隔离）：
--     open_user_bind / open_user_extend
-- =====================================================================

-- ---------------- 接入应用（全局表） ----------------
CREATE TABLE IF NOT EXISTS open_app
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    app_key        VARCHAR(64)  NOT NULL COMMENT '应用标识（自动生成，全局唯一，凭证 mse-{tenantId}-{hex} 40 字符）',
    app_secret     VARCHAR(120) NOT NULL COMMENT 'AppSecret 密文（AES-GCM，明文仅创建/重置时展示一次）',
    name           VARCHAR(60)  NOT NULL COMMENT '应用名称',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '业务绑定租户（0=平台域；开放 API 数据隔离锚点）',
    rate_limit_qps BIGINT       NULL COMMENT '限流 QPS（NULL=用 mse.open.default-rate-limit）',
    expire_time    DATETIME     NULL COMMENT '凭证过期时间（NULL=长期）',
    sign_required  TINYINT      NOT NULL DEFAULT 0 COMMENT '强制请求签名验签（0 关 1 开；HMAC-SHA256 + 时间戳防重放 + nonce）',
    ip_whitelist   VARCHAR(500) NULL COMMENT 'IP 白名单（逗号/分号/换行分隔，支持 IPv4 CIDR；空=不限制）',
    domain_whitelist VARCHAR(500) NULL COMMENT '域名白名单（逗号/分号/换行分隔，裸域名或 *.example.com 子域通配；空=不限制；约束浏览器侧 Origin/Referer 来源）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark         VARCHAR(255) NULL COMMENT '备注',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_key (app_key, deleted),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB COMMENT ='开放平台接入应用';

-- ---------------- 开放 API 清单（目录：SQL 种子 + 业务积木自注册，每世界一份） ----------------
CREATE TABLE IF NOT EXISTS open_api
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台公共目录；各租户副本经 tenant_seed 播种）',
    code        VARCHAR(64)  NOT NULL COMMENT 'API 标识（open:resource:action，世界内唯一）',
    name        VARCHAR(60)  NOT NULL COMMENT 'API 名称',
    path        VARCHAR(200) NOT NULL COMMENT '路径模板（Ant 风格，如 /open/api/user/{id}）',
    http_method VARCHAR(10)  NOT NULL DEFAULT 'GET' COMMENT 'HTTP 方法（大写；ALL=不限）',
    target      VARCHAR(300) NULL COMMENT '分发目标：契约接口全名#方法名（空=open-service 内置端点）',
    source      VARCHAR(10)  NOT NULL DEFAULT 'seed' COMMENT '目录来源：seed=SQL 种子；rpc=业务积木自注册',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark      VARCHAR(255) NULL COMMENT '备注',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_id, code, deleted)
) ENGINE = InnoDB COMMENT ='开放 API 清单';

-- ---------------- 应用-API 授权关系（全局表） ----------------
CREATE TABLE IF NOT EXISTS open_app_api
(
    id          BIGINT    NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT    NOT NULL COMMENT '租户编号（随应用绑定租户）',
    app_id      BIGINT    NOT NULL COMMENT '应用编号',
    api_id      BIGINT    NOT NULL COMMENT 'API 编号',
    create_by   BIGINT    NULL COMMENT '创建者',
    create_time DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT    NULL COMMENT '更新者',
    update_time DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT   NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_api (app_id, api_id, deleted)
) ENGINE = InnoDB COMMENT ='应用-API 授权关系';

-- ---------------- 调用审计日志（全局表） ----------------
CREATE TABLE IF NOT EXISTS open_api_log
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台域/未过鉴权）',
    app_id      BIGINT       NULL COMMENT '应用编号（未过 token 校验为空）',
    api_code    VARCHAR(64)  NULL COMMENT '命中的 API 标识（未匹配清单为空）',
    method      VARCHAR(10)  NULL COMMENT '请求 HTTP 方法',
    path        VARCHAR(200) NULL COMMENT '请求路径',
    ip          VARCHAR(64)  NULL COMMENT '来源 IP',
    status_code INT          NULL COMMENT 'HTTP 状态码',
    cost_ms     BIGINT       NULL COMMENT '耗时（毫秒）',
    error_msg   VARCHAR(500) NULL COMMENT '失败原因（鉴权/限流等）',
    trace_id    VARCHAR(64)  NULL COMMENT '链路追踪ID',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_app_id (app_id, id),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT ='开放 API 调用审计';

-- ---------------- 统一身份绑定（租户表） ----------------
CREATE TABLE IF NOT EXISTS open_user_bind
(
    id               BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    user_id          BIGINT       NOT NULL COMMENT '中台用户编号（sys_user.id）',
    source_system    VARCHAR(60)  NOT NULL COMMENT '来源系统标识（接入方自定）',
    external_user_id VARCHAR(128) NOT NULL COMMENT '外部系统用户标识',
    remark           VARCHAR(255) NULL COMMENT '备注',
    create_by        BIGINT       NULL COMMENT '创建者',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        BIGINT       NULL COMMENT '更新者',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bind (tenant_id, source_system, external_user_id, deleted),
    KEY idx_user (tenant_id, user_id)
) ENGINE = InnoDB COMMENT ='统一身份绑定（外部标识 ↔ 中台账号）';

-- ---------------- 用户扩展属性（租户表） ----------------
CREATE TABLE IF NOT EXISTS open_user_extend
(
    id          BIGINT   NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT   NOT NULL DEFAULT 0 COMMENT '租户编号',
    user_id     BIGINT   NOT NULL COMMENT '中台用户编号（租户内唯一）',
    extend_json TEXT     NULL COMMENT '扩展属性 JSON 对象（整体读写，≤4KB）',
    create_by   BIGINT   NULL COMMENT '创建者',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT   NULL COMMENT '更新者',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (tenant_id, user_id, deleted)
) ENGINE = InnoDB COMMENT ='用户扩展属性（接入系统差异化画像）';

-- =====================================================================
-- 开放 API 清单种子（id 8001+，tenant_id=0 平台公共目录；停用某 API 置 status=1 即全平台下线。
-- 仅登记内置端点条目；业务积木开放能力经 open-api 契约自注册（source=rpc），无需种子）
-- =====================================================================
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8001, 'open:user:page', '分页查询用户', '/open/api/user/page', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8001);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8002, 'open:user:get', '查询用户', '/open/api/user/{id}', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8002);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8003, 'open:user:get-by-username', '按用户名查询', '/open/api/user/by-username', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8003);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8004, 'open:user:create', '创建用户', '/open/api/user', 'POST', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8004);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8005, 'open:user:update', '更新用户', '/open/api/user', 'PUT', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8005);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8006, 'open:user:status', '启停用户', '/open/api/user/{id}/status', 'PUT', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8006);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8007, 'open:user:reset-password', '重置用户密码', '/open/api/user/{id}/password', 'PUT', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8007);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8008, 'open:user:delete', '删除用户', '/open/api/user/{id}', 'DELETE', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8008);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8009, 'open:user:role-ids', '查询用户角色', '/open/api/user/{id}/role-ids', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8009);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8010, 'open:user:assign-roles', '分配用户角色', '/open/api/user/{id}/role-ids', 'PUT', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8010);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8011, 'open:user:extend-get', '读用户扩展属性', '/open/api/user/{id}/extend', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8011);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8012, 'open:user:extend-put', '写用户扩展属性', '/open/api/user/{id}/extend', 'PUT', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8012);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8013, 'open:dept:tree', '部门树查询', '/open/api/dept/tree', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8013);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8014, 'open:dept:create', '创建部门', '/open/api/dept', 'POST', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8014);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8015, 'open:dept:update', '更新部门', '/open/api/dept', 'PUT', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8015);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8016, 'open:dept:delete', '删除部门', '/open/api/dept/{id}', 'DELETE', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8016);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8017, 'open:role:list', '角色列表查询', '/open/api/role/list', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8017);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8018, 'open:role:create', '创建角色', '/open/api/role', 'POST', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8018);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8019, 'open:bind:create', '创建身份绑定', '/open/api/bind', 'POST', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8019);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8020, 'open:bind:resolve', '外部标识解析', '/open/api/bind/resolve', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8020);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8021, 'open:bind:by-user', '查询用户全部绑定', '/open/api/bind/by-user/{userId}', 'GET', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8021);
INSERT INTO open_api (id, code, name, path, http_method, target, source, status, create_time, update_time)
SELECT 8022, 'open:bind:delete', '解除身份绑定', '/open/api/bind/{bindId}', 'DELETE', NULL, 'seed', 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM open_api WHERE id = 8022);
