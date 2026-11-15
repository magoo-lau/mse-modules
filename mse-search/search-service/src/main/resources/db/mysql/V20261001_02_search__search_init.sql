-- mse-target: master
-- =====================================================================
-- 搜索引擎服务业务表（独立工程 mse-modules/mse-search，MySQL）
-- 目标库：业务主库 + 各租户独立库（随服务启动/懒初始化按账本应用）
-- 配套：search_base.sql（基座通道：sys_micro_app/sys_menu/sys_permission）
-- 结构要点：目录真租户化——search_source/search_task/search_scan_cursor 带 tenant_id 与
--       租户维度唯一键（源/游标按租户独立记账，任务拾取按归属租户过滤）；
--       search_source 带 visible_roles（可见角色，与 permission_code 同时生效）
-- 目录播种：平台目录行由业务自注册推送扇出播种（SearchSourceFleetSeeder），无 SQL 种子
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行
-- =====================================================================

-- ---------------- 可搜索源（租户隔离表：每租户一份目录，平台=tenant 0 行） ----------------
CREATE TABLE IF NOT EXISTS search_source (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台自有目录）',
    source_code VARCHAR(64) NOT NULL COMMENT '源标识',
    source_name VARCHAR(128) DEFAULT NULL COMMENT '源名称',
    domain VARCHAR(64) DEFAULT NULL COMMENT '业务域显示名（如 OA域；空=不分域，仅参与全部域搜索）',
    provider_group VARCHAR(64) NOT NULL COMMENT '数据提供者分组（业务服务名）',
    jump_url_template VARCHAR(255) NOT NULL COMMENT '跳转模板（/sub/{app}/...，支持 {bizId}/{extra.字段}）',
    permission_code VARCHAR(128) DEFAULT NULL COMMENT '结果可见权限码（空=登录即可见）',
    visible_roles VARCHAR(512) DEFAULT NULL COMMENT '可见角色编码（逗号分隔，空=不限制；用户持有任一角色才可见该源，与 permission_code 同时生效）',
    data_scope_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '个人数据范围过滤：0 关闭 1 开启',
    scan_cron VARCHAR(32) DEFAULT NULL COMMENT '增量扫描 cron（秒 分 时 日 月 周）',
    incremental_column VARCHAR(64) DEFAULT 'update_time' COMMENT '增量水位列名',
    batch_size INT DEFAULT 500 COMMENT '每批拉取条数',
    shard_count INT DEFAULT 1 COMMENT '分片数',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 启用 1 停用',
    fields_json TEXT COMMENT '源字段配置（JSON 数组；空=无配置，查询退回内置 title/content）',
    fields_customized TINYINT NOT NULL DEFAULT 0 COMMENT '字段配置定制标记：0 跟随推送刷新 1 管理端已定制',
    remark VARCHAR(255) DEFAULT NULL COMMENT '说明',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_source_tenant (tenant_id, source_code, deleted),
    KEY idx_search_source_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='可搜索源';

-- ---------------- 扫描游标（租户隔离表：租户 × 源 × 分片增量水位） ----------------
CREATE TABLE IF NOT EXISTS search_scan_cursor (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    source_code VARCHAR(64) NOT NULL COMMENT '源标识',
    shard_index INT NOT NULL DEFAULT 0 COMMENT '分片号（0 起）',
    watermark VARCHAR(64) DEFAULT '0' COMMENT '增量水位下界（首次全量=0）',
    last_scan_time DATETIME DEFAULT NULL COMMENT '最近扫描时间（调度 cron 锚点）',
    fail_count INT DEFAULT 0 COMMENT '连续失败次数',
    last_error VARCHAR(500) DEFAULT NULL COMMENT '最近失败原因',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_cursor_tenant (tenant_id, source_code, shard_index, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='扫描游标';

-- ---------------- 搜索引擎任务（租户隔离表：全量重建/索引优化/立即扫描） ----------------
CREATE TABLE IF NOT EXISTS search_task (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    task_type VARCHAR(32) NOT NULL COMMENT '任务类型：FULL_REBUILD/INDEX_OPTIMIZE/SCAN_NOW',
    source_code VARCHAR(64) DEFAULT NULL COMMENT '目标源（SCAN_NOW/FULL_REBUILD 必填）',
    idle_only TINYINT NOT NULL DEFAULT 1 COMMENT '闲时专用：0 立即 1 仅闲时窗口内执行',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 待执行 1 执行中 2 成功 3 失败',
    progress INT DEFAULT 0 COMMENT '进度百分比（0-100）',
    processed_rows BIGINT DEFAULT NULL COMMENT '处理文档数',
    payload VARCHAR(500) DEFAULT NULL COMMENT '任务参数（JSON，预留）',
    error_msg VARCHAR(500) DEFAULT NULL COMMENT '失败原因',
    start_time DATETIME DEFAULT NULL,
    end_time DATETIME DEFAULT NULL,
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    KEY idx_search_task_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='搜索引擎任务';

-- ---------------- 搜索热词（租户隔离表：查询链路异步累加） ----------------
CREATE TABLE IF NOT EXISTS search_hot_keyword (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    keyword VARCHAR(64) NOT NULL COMMENT '搜索关键词',
    hit_count BIGINT DEFAULT 1 COMMENT '命中次数',
    last_search_time DATETIME DEFAULT NULL COMMENT '最近搜索时间',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_hot_keyword (tenant_id, keyword, deleted),
    KEY idx_search_hot_hit (tenant_id, hit_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='搜索热词';

-- ---------------- 租户字段覆盖（租户隔离表：当前租户 × 源的查询层覆盖） ----------------
CREATE TABLE IF NOT EXISTS search_tenant_field (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    source_code VARCHAR(64) NOT NULL COMMENT '源标识（须为归属目录已登记源）',
    fields_json TEXT COMMENT '覆盖字段配置（JSON 数组；仅 searchable/weight/highlightable/returned/label 生效，新增字段须带 type）',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_search_tenant_field (tenant_id, source_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户搜索字段覆盖';
