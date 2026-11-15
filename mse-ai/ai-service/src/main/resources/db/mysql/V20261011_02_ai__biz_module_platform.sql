-- mse-target: base
-- mse-base-scope: platform
SET NAMES utf8mb4;
-- =====================================================================
-- AI 业务模块注册中心·平台库份（与 master 通道 V20261011_01 同构，结构变更两处同步）
-- 目标库：平台库（一切皆租户——平台=租户 0 的专属库）
-- 模块目录=全局目录（平台库 0 行，读取走 runIgnore）；声明登记写业务主库 0 行
-- （平台登记源），平台库行为管理端治理与运行期读取提供结构。
-- =====================================================================

CREATE TABLE IF NOT EXISTS ai_biz_module
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '归属租户（仅 0=平台全局目录）',
    module_code    VARCHAR(64)  NOT NULL COMMENT '模块编码（世界内唯一，如 oa/mes/mp；biz_query 的 module 入参）',
    name           VARCHAR(100) NOT NULL COMMENT '模块名称（如 协同办公）',
    description    VARCHAR(500) NULL COMMENT '功能描述（模块业务范围，供模型判定问题归属）',
    service_name   VARCHAR(64)  NOT NULL COMMENT '归属服务名（模块查询工具的 Dubbo group）',
    keywords       VARCHAR(500) NULL COMMENT '领域关键词（逗号分隔，供模型判定问题归属）',
    table_prefixes VARCHAR(200) NULL COMMENT '业务表前缀（逗号分隔小写，模块工具放行白名单口径）',
    tool_code      VARCHAR(100) NOT NULL COMMENT '模块查询工具编码（ai_tool.tool_code，如 oa_biz_query）',
    source         VARCHAR(20)  NOT NULL DEFAULT 'rpc' COMMENT 'local 单体声明登记 / rpc 微服务推送登记',
    rpc_group      VARCHAR(64)  NULL COMMENT '声明提供方服务名（source=rpc 时登记，防同码互相覆盖）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark         VARCHAR(255) NULL COMMENT '备注',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_module_world_code (tenant_id, module_code, deleted)
) ENGINE = InnoDB COMMENT ='业务模块注册（平台全局目录，biz_query 路由门面数据源）';
