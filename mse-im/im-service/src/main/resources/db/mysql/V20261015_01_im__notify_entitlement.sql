-- mse-target: platform
SET NAMES utf8mb4;
-- =====================================================================
-- IM 通知渠道权益项种子（独立工程 mse-modules/mse-im，MySQL；平台库）
-- 内容：feature.im.notify 功能开关（BOOL，默认 false）——协同服务（mse-oa）通知中枢的
--       IM 渠道为授权制：租户默认不可用，平台管理员经基座「附加项管理/租户权益覆盖」
--       （或套餐 sys_package_item）开通后才可在通知渠道设置中勾选与投递。
--       变更即 evict + TenantEvent.FEATURE_CHANGED，快照失效链秒级生效。
-- 幂等：NOT EXISTS 判重，可重复执行
-- 号段：权益项 id 4651（im 段族 46xx 内自选百位小段 4650+，登记见 alloc-tables.md 第 11 节）
-- =====================================================================

INSERT INTO sys_entitlement_item (id, item_code, item_type, name, value_type, default_value, remark, status, create_time, update_time)
SELECT 4651, 'feature.im.notify', 2, 'IM 通知渠道', 'BOOL', 'false', '通知按渠道派发的 IM 单聊推送（协同服务通知中枢消费；默认关闭，平台按租户授权开通）', 0, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_entitlement_item x WHERE x.id = 4651);
