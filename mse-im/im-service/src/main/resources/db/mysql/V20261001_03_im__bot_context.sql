-- mse-target: master
-- =====================================================================
-- mse-im 机器人上下文感知（im_msg_audit 增强：发送者昵称快照 + 会话查询索引）
-- 机器人回复链路升级为「审计表注入式」：触发时从消息存档拉本会话最近 N 条
-- （含全部成员消息与机器人自己的历史回复）拼进提问（见 ImBotContextService），需要：
--   sender_nickname：webhook body 自带 sendNickname，落库即昵称快照，上下文渲染免二次查询；
--   idx_im_msg_audit_group：(tenant_id, group_id, id) 群聊上下文按群倒序取最近 N 条；
--   idx_im_msg_audit_pair：(tenant_id, send_id, recv_id, id) 单聊上下文双向取最近 N 条。
-- 目标库：业务主库（与 V20261001_02_im__im_init.sql 同库同表）
-- 幂等：MySQL 无 ADD COLUMN / ADD INDEX IF NOT EXISTS，用 information_schema 判重 +
--       PREPARE 动态 SQL（迁移器按分号拆句、同一连接逐句执行，用户变量跨句可用）
-- =====================================================================

-- ---------------- 加列：sender_nickname ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE im_msg_audit ADD COLUMN sender_nickname VARCHAR(64) NULL COMMENT ''发送者昵称快照（webhook sendNickname，历史行 NULL 回落 send_id 展示）'' AFTER content_text',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_msg_audit' AND COLUMN_NAME = 'sender_nickname');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 加索引：群聊上下文查询 ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE im_msg_audit ADD INDEX idx_im_msg_audit_group (tenant_id, group_id, id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_msg_audit' AND INDEX_NAME = 'idx_im_msg_audit_group');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 加索引：单聊上下文双向查询 ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE im_msg_audit ADD INDEX idx_im_msg_audit_pair (tenant_id, send_id, recv_id, id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'im_msg_audit' AND INDEX_NAME = 'idx_im_msg_audit_pair');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
