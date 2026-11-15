-- mse-target: base
-- mse-base-scope: platform
-- =====================================================================
-- 文件存储平台库结构脚本（分库形态配套，monitor 平台域脚本同款范式）
-- 目标库：基座库/平台库（mse_platform；base 通道平台域应用——单体/基座应用启动本地直建，
--         微服务形态经 starter-bridge 推送 mse-system 应用到其主库）
-- 背景：平台会话（tenant_id=0）的全部 fs_file 语句经路由拦截器落平台库（单体双库形态
--       注册了 platform 数据源），fs_file 必须在平台库就绪——否则平台侧一切上传
--       （平台头像 /platform/avatar、平台域名个性化素材等）撞缺表；master 通道的
--       fs_init.sql 仍负责业务库副本（fs-boot 微服务未注册 platform 数据源、
--       平台会话回落主库，业务库 fs_file 仍在服役），两通道脚本幂等互不冲突
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行
-- =====================================================================

-- ---------------- 文件元数据（平台库自持副本，平台会话上传落 tenant_id=0 行） ----------------
CREATE TABLE IF NOT EXISTS fs_file
(
    id           BIGINT        NOT NULL COMMENT '主键ID（雪花，物理对象键同名）',
    tenant_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '租户编号（平台会话上传恒为 0）',
    file_name    VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    storage_key  VARCHAR(512)  NOT NULL COMMENT '存储键（{tenantId}/{yyyyMM}/{fileId}.{ext}）',
    storage_type VARCHAR(16)   NOT NULL COMMENT '存储类型：local / rustfs',
    content_type VARCHAR(128)  NULL COMMENT '内容 MIME 类型',
    file_size    BIGINT        NOT NULL DEFAULT 0 COMMENT '字节数',
    biz_type     VARCHAR(64)   NULL COMMENT '业务分类（如 user-avatar / tenant-branding）',
    biz_id       VARCHAR(64)   NULL COMMENT '业务关联 ID',
    access_level VARCHAR(16)   NOT NULL DEFAULT 'tenant' COMMENT '访问级别：tenant 登录本租户 / public 匿名可读（/fs/asset/{tenantId}/{fileId}.{ext}）',
    content_md5  VARCHAR(32)   NULL COMMENT '内容 MD5（32 位小写 hex；同租户同 MD5 秒传复用物理对象）',
    upload_id    VARCHAR(256)  NULL COMMENT '分片直传会话 ID（仅 rustfs 直传进行中非空，complete/abort 后清空/删行）',
    create_by    BIGINT        NULL COMMENT '创建者',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    BIGINT        NULL COMMENT '更新者',
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_biz (biz_type, biz_id),
    KEY idx_create_time (create_time),
    KEY idx_md5 (content_md5)
) ENGINE = InnoDB COMMENT ='文件元数据·平台库副本（物理对象按 storage_type 由 FileStorage 实现保管）';
