-- mse-target: master
-- =====================================================================
-- 文件存储服务初始化脚本（独立工程 mse-modules/mse-fs，MySQL）
-- 目标库：业务库（业务表所在库；master 默认库 mse_tenant（缺省共享租户库），首启自动建库；分库部署对业务库执行）
-- 内容：fs_* 业务表
-- 配套：fs_base.sql（对基座库执行：子应用注册 + 页面菜单 + 权限点）
--
-- 前置：无（基座库随基座应用启动自动初始化；本工程 fs_base.sql 随 jar 自动推送注册）
-- 执行：自动——随服务启动应用主库（mse.tenant.schema.auto-migrate 默认开启）；租户独立库经「租户数据源初始化/懒初始化」克隆+迁移自动就绪
-- 版本化：V{yyyyMMdd}_{NN}_<biz>__<描述>.sql（biz 段路由分册账本 mse_<biz>_schema_history，同 biz 内字典序即时间序）；
--           已应用脚本不再修改（改动报 checksum 冲突），变更一律新增版本文件
--
-- 多租户：fs_file 带 tenant_id 经基座租户插件自动隔离；物理对象按 {tenantId}/ 前缀隔离
-- =====================================================================

-- ---------------- 文件元数据 ----------------
CREATE TABLE IF NOT EXISTS fs_file
(
    id           BIGINT        NOT NULL COMMENT '主键ID（雪花，物理对象键同名）',
    tenant_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '租户编号',
    file_name    VARCHAR(255)  NOT NULL COMMENT '原始文件名',
    storage_key  VARCHAR(512)  NOT NULL COMMENT '存储键（{tenantId}/{yyyyMM}/{fileId}.{ext}）',
    storage_type VARCHAR(16)   NOT NULL COMMENT '存储类型：local / rustfs',
    content_type VARCHAR(128)  NULL COMMENT '内容 MIME 类型',
    file_size    BIGINT        NOT NULL DEFAULT 0 COMMENT '字节数',
    biz_type     VARCHAR(64)   NULL COMMENT '业务分类（如 oa/attachment）',
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
) ENGINE = InnoDB COMMENT ='文件元数据（物理对象按 storage_type 由 FileStorage 实现保管）';
