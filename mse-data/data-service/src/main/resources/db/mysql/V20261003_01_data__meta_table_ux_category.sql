-- mse-target: master
-- =====================================================================
-- mse-data 元数据表唯一键修复：ux_ds_table 增加 category 维度
-- 缺陷（2026-10-03 用例 DATA-META-002 实测）：原唯一键 (tenant_id, datasource_id, table_name, deleted)
-- 不含 category，同一数据源同一张表先采集 ods 再采集 source（或反之）时第二条必撞唯一键
-- （400「保存失败：编码/键名已存在」），重复采集亦不幂等。业务语义 = 每类目各持一份表清单，
-- 唯一键应为 (tenant_id, datasource_id, table_name, category, deleted)。
-- 目标库：业务主库（与 V20261001_02_data__data_init.sql 同库同表）
-- 幂等：MySQL 无 DROP/ADD INDEX IF NOT EXISTS，用 information_schema 判重 +
--       PREPARE 动态 SQL（迁移器按分号拆句、同一连接逐句执行，用户变量跨句可用）
-- =====================================================================

-- ---------------- 加新唯一键（含 category；旧键约束下数据无同 ds+表跨类目重复，加键必成功） ----------------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE dat_meta_table ADD UNIQUE KEY ux_ds_table_cat (tenant_id, datasource_id, table_name, category, deleted)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dat_meta_table' AND INDEX_NAME = 'ux_ds_table_cat');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------- 删旧唯一键（存在才删） ----------------
SET @ddl = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE dat_meta_table DROP INDEX ux_ds_table',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dat_meta_table' AND INDEX_NAME = 'ux_ds_table');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
