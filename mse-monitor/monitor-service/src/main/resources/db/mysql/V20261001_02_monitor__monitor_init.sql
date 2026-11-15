-- mse-target: master
-- =====================================================================
-- 监控中心初始化脚本（独立工程 mse-modules/mse-monitor，MySQL）
-- 目标库：业务库（monitor 自己的库；单库演示对 mse 执行）
-- 内容：mon_* 业务表 + 种子（通知设置默认行 + 本平台监控目标全家桶）
-- 配套：monitor_base.sql（对基座库执行：sys_micro_app/sys_menu/sys_permission）
-- 幂等：CREATE TABLE IF NOT EXISTS + 种子 NOT EXISTS，可重复执行
-- 执行：自动——随服务启动应用主库（mse.tenant.schema.auto-migrate 默认开启）；租户独立库经「租户数据源初始化/懒初始化」克隆+迁移自动就绪
-- 版本化：V{yyyyMMdd}_{NN}_<biz>__<描述>.sql（biz 段路由分册账本 mse_<biz>_schema_history，同 biz 内字典序即时间序）；
--           已应用脚本不再修改（改动报 checksum 冲突），变更一律新增版本文件
-- 号段：本工程业务表自建；种子行用 1-16 小 id
-- 启用建议：种子目标默认仅 MySQL/Redis 启用（单机演示必有），其余按部署实况启停
-- =====================================================================

-- ---------------- 监控目标（平台级：服务/HTTP/JVM/慢SQL/MySQL/Redis/RocketMQ/OS） ----------------
CREATE TABLE IF NOT EXISTS mon_target (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    name VARCHAR(64) NOT NULL COMMENT '目标名称',
    target_type VARCHAR(20) NOT NULL COMMENT '类型：service/http/jvm/slowsql/mysql/redis/rocketmq/os',
    service_url VARCHAR(255) DEFAULT NULL COMMENT '服务/HTTP 地址（http://host:port）',
    host VARCHAR(64) DEFAULT NULL COMMENT '主机（mysql/redis/rocketmq；os 固定本机）',
    port INT DEFAULT NULL COMMENT '端口',
    username VARCHAR(64) DEFAULT NULL COMMENT '认证用户名',
    password VARCHAR(128) DEFAULT NULL COMMENT '认证密码（界面脱敏）',
    collect_interval INT DEFAULT 30 COMMENT '采集间隔（秒）',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 启用 1 停用',
    alert_config VARCHAR(1024) DEFAULT NULL COMMENT '告警规则 JSON：{down,latencyMs,cpuPercent,memPercent,diskPercent,redisMemPercent,mysqlMaxThreads}',
    status VARCHAR(20) DEFAULT 'unknown' COMMENT '最近状态：up/down/unknown',
    status_time DATETIME DEFAULT NULL COMMENT '最近状态时间',
    last_latency_ms INT DEFAULT NULL COMMENT '最近响应耗时（ms）',
    remark VARCHAR(255) DEFAULT NULL COMMENT '说明',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    KEY idx_mon_target_type (target_type),
    KEY idx_mon_target_status (enabled, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='监控目标';

-- ---------------- 监控采样记录（保留期由每日清理任务维护，默认 7 天） ----------------
CREATE TABLE IF NOT EXISTS mon_record (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    target_id BIGINT NOT NULL COMMENT '监控目标',
    collect_time DATETIME NOT NULL COMMENT '采集时间',
    status VARCHAR(20) NOT NULL COMMENT '采集状态：up/down',
    latency_ms INT DEFAULT NULL COMMENT '响应耗时（ms）',
    metrics TEXT COMMENT '指标集 JSON（按类型：cpu_usage_pct/threads_connected/qps…）',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    KEY idx_mon_record_target (target_id, collect_time),
    KEY idx_mon_record_time (collect_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='监控采样记录';

-- ---------------- 监控告警（状态机：firing 触发中 / resolved 已恢复） ----------------
CREATE TABLE IF NOT EXISTS mon_alert (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    target_id BIGINT NOT NULL COMMENT '监控目标',
    target_name VARCHAR(64) DEFAULT NULL COMMENT '目标名称（冗余，目标删除后仍可读）',
    level VARCHAR(10) NOT NULL DEFAULT 'warn' COMMENT '级别：warn/error',
    rule_key VARCHAR(30) NOT NULL COMMENT '规则键：down/latency/cpu/mem/disk/redis_mem/mysql_threads',
    content VARCHAR(500) DEFAULT NULL COMMENT '告警内容',
    status VARCHAR(20) NOT NULL DEFAULT 'firing' COMMENT '状态：firing/resolved',
    first_trigger_time DATETIME DEFAULT NULL COMMENT '首次触发时间',
    last_trigger_time DATETIME DEFAULT NULL COMMENT '最近触发时间',
    resolve_time DATETIME DEFAULT NULL COMMENT '恢复时间',
    notify_result VARCHAR(500) DEFAULT NULL COMMENT '通知结果 JSON（inbox/webhook）',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    KEY idx_mon_alert_target (target_id, status),
    KEY idx_mon_alert_time (last_trigger_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='监控告警';

-- ---------------- 监控服务配置（key-value，当前 key=notify 告警通知设置，界面维护） ----------------
CREATE TABLE IF NOT EXISTS mon_setting (
    id BIGINT NOT NULL COMMENT '主键（雪花）',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台）',
    config_key VARCHAR(50) NOT NULL COMMENT '配置键（notify）',
    config_value VARCHAR(2000) DEFAULT NULL COMMENT '配置值 JSON（NotifySetting）',
    create_by BIGINT DEFAULT NULL,
    create_time DATETIME DEFAULT NULL,
    update_by BIGINT DEFAULT NULL,
    update_time DATETIME DEFAULT NULL,
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mon_setting_key (tenant_id, config_key, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='监控服务配置';

-- ---------------- 种子：告警通知设置默认行（站内信 → admin(1)；此后在界面维护） ----------------
INSERT INTO mon_setting (id, tenant_id, config_key, config_value, create_time, update_time)
SELECT 1, 0, 'notify',
       '{"inboxEnabled":true,"inboxUsers":[{"id":1,"name":"admin"}],"webhookEnabled":false,"webhookUrl":"","webhookFormat":"dingtalk","repeatNotifyMinutes":0}',
       NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM mon_setting WHERE config_key = 'notify' AND tenant_id = 0 AND deleted = 0);

-- ---------------- 种子：本平台监控目标全家桶（按 id 判重；enabled=1 为停用，按部署实况启用） ----------------
INSERT INTO mon_target (id, tenant_id, name, target_type, service_url, host, port, username, password, collect_interval, enabled, alert_config, status, status_time, remark, create_time, update_time)
SELECT t.id, 0, t.name, t.target_type, t.service_url, t.host, t.port, t.username, t.password, t.collect_interval, t.enabled, t.alert_config, 'unknown', NULL, t.remark, NOW(), NOW()
FROM (
    SELECT 1 AS id, 'mse-gateway' AS name, 'service' AS target_type, 'http://localhost:48080' AS service_url, NULL AS host, NULL AS port, NULL AS username, NULL AS password, 30 AS collect_interval, 1 AS enabled, '{"down":true,"latencyMs":3000}' AS alert_config, '网关' AS remark
    UNION ALL SELECT 2, 'mse-auth',   'service', 'http://localhost:48083', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '认证服务'
    UNION ALL SELECT 3, 'mse-system', 'service', 'http://localhost:48081', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '系统管理服务'
    UNION ALL SELECT 4, 'mse-tenant', 'service', 'http://localhost:48082', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '租户管理服务'
    UNION ALL SELECT 5, 'mse-oa',     'service', 'http://localhost:8090', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '协同办公'
    UNION ALL SELECT 6, 'mse-mp',     'service', 'http://localhost:8091', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '微信公众号'
    UNION ALL SELECT 7, 'mse-open',   'service', 'http://localhost:8092', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '开放平台'
    UNION ALL SELECT 8, 'mse-data',   'service', 'http://localhost:8093', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '数据中台'
    UNION ALL SELECT 9, 'mse-ai',     'service', 'http://localhost:8094', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', 'AI 智能体平台'
    UNION ALL SELECT 10, 'mse-fs',    'service', 'http://localhost:8095', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '文件存储'
    UNION ALL SELECT 11, 'mse-search','service', 'http://localhost:8096', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"latencyMs":3000}', '搜索引擎'
    UNION ALL SELECT 12, 'MySQL 主库', 'mysql', NULL, 'localhost', 3306, 'root', '123456', 30, 0, '{"down":true,"mysqlMaxThreads":500}', '业务/基座库（默认启用）'
    UNION ALL SELECT 13, 'Redis 缓存', 'redis', NULL, 'localhost', 6379, NULL, NULL, 30, 0, '{"down":true,"redisMemPercent":90}', '缓存/会话（默认启用）'
    UNION ALL SELECT 14, 'Nacos',      'http',  'http://localhost:18080', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"latencyMs":3000}', '注册/配置中心（控制台端口，按需启用）'
    UNION ALL SELECT 15, 'Elasticsearch', 'http', 'http://localhost:9200/_cluster/health', NULL, NULL, NULL, NULL, 60, 1, '{"down":true}', '搜索引擎中间件（按需启用）'
    UNION ALL SELECT 16, 'RocketMQ',   'rocketmq', NULL, 'localhost', 9876, NULL, NULL, 60, 1, '{"down":true}', '消息队列 namesrv（按需启用）'
    UNION ALL SELECT 21, 'mse-gateway JVM', 'jvm', 'http://localhost:48080', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '网关 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 22, 'mse-auth JVM',    'jvm', 'http://localhost:48083', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '认证服务 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 23, 'mse-system JVM',  'jvm', 'http://localhost:48081', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '系统管理服务 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 24, 'mse-tenant JVM',  'jvm', 'http://localhost:48082', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '租户管理服务 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 25, 'mse-oa JVM',      'jvm', 'http://localhost:8090', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '协同办公 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 26, 'mse-mp JVM',      'jvm', 'http://localhost:8091', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '微信公众号 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 27, 'mse-open JVM',    'jvm', 'http://localhost:8092', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '开放平台 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 28, 'mse-data JVM',    'jvm', 'http://localhost:8093', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '数据中台 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 29, 'mse-ai JVM',      'jvm', 'http://localhost:8094', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', 'AI 智能体平台 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 30, 'mse-fs JVM',      'jvm', 'http://localhost:8095', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '文件存储 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 31, 'mse-search JVM',  'jvm', 'http://localhost:8096', NULL, NULL, NULL, NULL, 30, 1, '{"down":true,"jvmHeapPercent":85}', '搜索引擎 JVM（启用前对齐 exposure 含 metrics）'
    UNION ALL SELECT 41, 'mse-system 慢SQL', 'slowsql', 'http://localhost:48081', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 42, 'mse-tenant 慢SQL', 'slowsql', 'http://localhost:48082', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 43, 'mse-oa 慢SQL',     'slowsql', 'http://localhost:8090', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 44, 'mse-mp 慢SQL',     'slowsql', 'http://localhost:8091', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 45, 'mse-open 慢SQL',   'slowsql', 'http://localhost:8092', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 46, 'mse-data 慢SQL',   'slowsql', 'http://localhost:8093', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 47, 'mse-ai 慢SQL',     'slowsql', 'http://localhost:8094', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 48, 'mse-fs 慢SQL',     'slowsql', 'http://localhost:8095', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
    UNION ALL SELECT 49, 'mse-search 慢SQL', 'slowsql', 'http://localhost:8096', NULL, NULL, NULL, NULL, 60, 1, '{"down":true,"slowSqlCount":10}', '服务需开启 mse.mybatis.slow-sql.enabled 且 exposure 含 slowsql'
) t
WHERE NOT EXISTS (SELECT 1 FROM mon_target m WHERE m.id = t.id);

-- 本机操作系统（target_type=os）由服务启动后调度首轮自动创建，无需种子
