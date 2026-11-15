-- mse-target: master
-- =====================================================================
-- 修订：im_workbench_item.app_key 列长 VARCHAR(32) -> VARCHAR(64)
-- 根因：开放平台 app_key 凭证形如 mse-{tenantId}-{hex} 共 40 字符（open_app.app_key 即按
--       VARCHAR(64) 建列），工作台免登入口保存冗余落列时 Data too long 截断异常，
--       im_workbench_item save/jump 全链 500
-- 幂等：MODIFY 到同定义重复执行无害
-- =====================================================================
ALTER TABLE im_workbench_item
    MODIFY COLUMN app_key VARCHAR(64) NULL COMMENT '免登授权绑定的开放平台应用 app_key（保存时冗余落列，auth_mode=1 必填）';
