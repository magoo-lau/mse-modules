-- mse-target: master
SET NAMES utf8mb4;
-- =====================================================================
-- 知识库声明式注册（AiKbProvider/AiKbDescriptor + 智能体 kbCodes 声明绑定）：
--   ①新表 ai_kb_decl：声明库壳编码目录（平台全局目录，仅 tenant_id=0 行，读取走 runIgnore）；
--   ②ai_agent 加列 kb_codes：声明绑定知识库编码 CSV（提供方权威刷新字段），租户副本懒补齐时
--     按编码在本世界幂等建库壳（ai_knowledge_base.code 同码行）解析为 kb_ids 回填；
--   ③ai_knowledge_base 加列 code + 唯一键 uk_tenant_code：声明库壳的业务标识
--     （手工库 code 为 NULL，MySQL 唯一键允许多 NULL 不受影响）。
-- 目标库：业务主库 + 各租户独立库；平台库份见 V20261013_02（结构两处同步）。
-- 幂等：建表 IF NOT EXISTS；MySQL 无 ADD COLUMN/ADD INDEX IF NOT EXISTS，
--       用 information_schema 判重 + PREPARE 动态 SQL（迁移器按分号拆句、同一连接逐句执行）。
-- =====================================================================

-- ---------------- 新表：知识库声明目录 ----------------
CREATE TABLE IF NOT EXISTS ai_kb_decl
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '归属租户（仅 0=平台全局目录）',
    code        VARCHAR(64)  NOT NULL COMMENT '知识库编码（世界内唯一，如 oa-policy；智能体 kbCodes 引用此编码）',
    name        VARCHAR(100) NOT NULL COMMENT '名称（各租户库壳创建时的展示名）',
    description VARCHAR(500) NULL COMMENT '描述（管理页展示）',
    source      VARCHAR(20)  NOT NULL DEFAULT 'local' COMMENT 'local 单体声明登记 / rpc 微服务推送登记',
    rpc_group   VARCHAR(64)  NULL COMMENT '声明提供方服务名（source=rpc 时登记，防同码互相覆盖）',
    remark      VARCHAR(255) NULL COMMENT '备注',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_decl_code (tenant_id, code, deleted)
) ENGINE = InnoDB COMMENT = '知识库声明目录（平台全局目录，声明库壳编码元数据）';

-- ---------------- 加列：ai_agent.kb_codes ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ai_agent ADD COLUMN kb_codes VARCHAR(200) NULL COMMENT ''声明绑定知识库编码列表（逗号分隔 ai_knowledge_base.code；副本懒补齐时按编码建壳回填 kb_ids）'' AFTER kb_ids',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'kb_codes');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 加列：ai_knowledge_base.code ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ai_knowledge_base ADD COLUMN code VARCHAR(64) NULL COMMENT ''知识库编码（声明库壳通道，智能体 kbCodes 引用；手工库为 NULL）'' AFTER name',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_base' AND COLUMN_NAME = 'code');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 加唯一键：声明编码世界内唯一（含 deleted 位，删除库壳后自愈重建不撞已删行） ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE ai_knowledge_base ADD UNIQUE KEY uk_tenant_code (tenant_id, code, deleted)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_base' AND INDEX_NAME = 'uk_tenant_code');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
