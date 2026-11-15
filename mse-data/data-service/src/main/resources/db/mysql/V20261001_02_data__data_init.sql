-- mse-target: master
-- =====================================================================
-- 多租户数据中台初始化脚本（独立工程 mse-modules/mse-data，MySQL）
-- 目标库：业务库（业务表所在库；master 默认库 mse_platform，首启自动建库；分库部署对业务库执行）
-- 内容：dat_* 业务表
-- 配套：data_base.sql（对基座库执行：子应用注册 + 页面菜单 + 权限点）
--
-- 前置：无（基座库随基座应用启动自动初始化；本工程 data_base.sql 随 jar 自动推送注册）
-- 执行：自动——随服务启动应用主库（mse.tenant.schema.auto-migrate 默认开启）；租户独立库经「租户数据源初始化/懒初始化」克隆+迁移自动就绪
-- 版本化：V{yyyyMMdd}_{NN}_<biz>__<描述>.sql（biz 段路由分册账本 mse_<biz>_schema_history，同 biz 内字典序即时间序）；
--           已应用脚本不再修改（改动报 checksum 冲突），变更一律新增版本文件
--
-- 多租户：所有 dat_* 表带 tenant_id，经基座租户插件自动隔离；
--         同步生成的 ODS 贴源表（ods_*）由引擎动态创建，同样带 tenant_id 列
-- =====================================================================

-- ---------------- 外部数据源注册 ----------------
CREATE TABLE IF NOT EXISTS dat_datasource
(
    id              BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    name            VARCHAR(100) NOT NULL COMMENT '数据源名称',
    type            VARCHAR(20)  NOT NULL COMMENT 'mysql / dm / kingbase',
    host            VARCHAR(255) NOT NULL COMMENT '主机地址',
    port            INT          NOT NULL COMMENT '端口',
    db_name         VARCHAR(100) NOT NULL COMMENT '库名/schema',
    extra_params    VARCHAR(500) NULL COMMENT 'JDBC 追加参数（如 useSSL=false&…）',
    username        VARCHAR(100) NOT NULL COMMENT '用户名',
    password        VARCHAR(255) NOT NULL COMMENT '口令密文（AES-GCM）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    last_test_time  DATETIME     NULL COMMENT '最近连通性测试时间',
    last_test_ok    TINYINT      NULL COMMENT '最近测试结果：0 失败 1 成功',
    last_test_msg   VARCHAR(500) NULL COMMENT '最近测试失败原因',
    remark          VARCHAR(255) NULL COMMENT '备注',
    create_by       BIGINT       NULL COMMENT '创建者',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by       BIGINT       NULL COMMENT '更新者',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB COMMENT ='外部数据源注册';

-- ---------------- 同步任务 ----------------
CREATE TABLE IF NOT EXISTS dat_sync_job
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    name           VARCHAR(100) NOT NULL COMMENT '任务名称',
    datasource_id  BIGINT       NOT NULL COMMENT '来源数据源',
    source_type    VARCHAR(10)  NOT NULL DEFAULT 'table' COMMENT 'table 表 / sql 自定义查询',
    source_table   VARCHAR(200) NULL COMMENT '源表名（source_type=table）',
    query_sql      TEXT         NULL COMMENT '自定义查询 SQL（source_type=sql，仅 SELECT）',
    target_table   VARCHAR(100) NOT NULL COMMENT '目标 ODS 表（引擎自动建，ods_ 前缀）',
    sync_mode      VARCHAR(10)  NOT NULL DEFAULT 'full' COMMENT 'full 全量 / incr 增量',
    incr_column    VARCHAR(100) NULL COMMENT '增量水位列（时间戳/自增 ID）',
    watermark      VARCHAR(64)  NULL COMMENT '当前水位值（引擎回写）',
    cron           VARCHAR(40)  NULL COMMENT '调度 cron（Spring 表达式，空=仅手动）',
    dep_job_id     BIGINT       NULL COMMENT '前置任务（成功完成后级联触发本任务）',
    row_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '最近一次同步行数',
    last_run_time  DATETIME     NULL COMMENT '最近执行时间',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark         VARCHAR(255) NULL COMMENT '备注',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_ds (tenant_id, datasource_id)
) ENGINE = InnoDB COMMENT ='数据集成同步任务';

-- ---------------- 同步运行日志 ----------------
CREATE TABLE IF NOT EXISTS dat_sync_job_log
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    job_id         BIGINT       NOT NULL COMMENT '同步任务ID',
    trigger_type   VARCHAR(10)  NOT NULL COMMENT 'manual 手动 / sched 调度',
    status         VARCHAR(10)  NOT NULL COMMENT 'running / success / failed',
    sync_mode      VARCHAR(10)  NOT NULL COMMENT '本次执行模式 full / incr',
    row_count      BIGINT       NOT NULL DEFAULT 0 COMMENT '同步行数',
    watermark_used VARCHAR(64)  NULL COMMENT '执行前水位',
    watermark_new  VARCHAR(64)  NULL COMMENT '执行后水位',
    cost_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    error_msg      VARCHAR(1000) NULL COMMENT '错误信息',
    start_time     DATETIME     NOT NULL COMMENT '开始时间',
    end_time       DATETIME     NULL COMMENT '结束时间',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_job (tenant_id, job_id, start_time)
) ENGINE = InnoDB COMMENT ='同步任务运行日志';

-- ---------------- 元数据：表 ----------------
CREATE TABLE IF NOT EXISTS dat_meta_table
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    datasource_id  BIGINT       NOT NULL COMMENT '0 = 中台内部（ODS/中台库自身）',
    table_schema   VARCHAR(100) NULL COMMENT '所属 schema',
    table_name     VARCHAR(100) NOT NULL COMMENT '表名',
    category       VARCHAR(10)  NOT NULL DEFAULT 'source' COMMENT 'source 源库表 / ods 中台贴源表',
    table_comment  VARCHAR(500) NULL COMMENT '表注释',
    row_count      BIGINT       NULL COMMENT ' approximate 行数',
    collected_time DATETIME     NOT NULL COMMENT '最近采集时间',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY ux_ds_table (tenant_id, datasource_id, table_name, deleted),
    KEY idx_tenant_cat (tenant_id, category)
) ENGINE = InnoDB COMMENT ='元数据-表';

-- ---------------- 元数据：列 ----------------
CREATE TABLE IF NOT EXISTS dat_meta_column
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    meta_table_id  BIGINT       NOT NULL COMMENT '所属 dat_meta_table',
    column_name    VARCHAR(100) NOT NULL COMMENT '列名',
    data_type      VARCHAR(100) NOT NULL COMMENT '数据类型',
    nullable       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可空（1 是 0 否）',
    column_comment VARCHAR(500) NULL COMMENT '列注释',
    ordinal        INT          NOT NULL DEFAULT 0 COMMENT '列序号',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_table (tenant_id, meta_table_id)
) ENGINE = InnoDB COMMENT ='元数据-列';

-- ---------------- 数据服务 API ----------------
CREATE TABLE IF NOT EXISTS dat_api
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    name           VARCHAR(100) NOT NULL COMMENT 'API 名称',
    path           VARCHAR(100) NOT NULL COMMENT '开放路径 /openapi/data/{path}，租户内唯一',
    datasource_id  BIGINT       NOT NULL COMMENT '查询的数据源（0 = 中台库）',
    sql_template   TEXT         NOT NULL COMMENT 'SQL 模板（仅 SELECT，:param 占位）',
    params_json    VARCHAR(2000) NULL COMMENT '参数定义 JSON [{name,type,required,remark}]',
    page_enabled   TINYINT      NOT NULL DEFAULT 1 COMMENT '0 关 1 开（pageNum/pageSize 自动分页包裹）',
    max_rows       INT          NOT NULL DEFAULT 1000 COMMENT '单次返回行数上限',
    timeout_ms     INT          NOT NULL DEFAULT 10000 COMMENT '查询超时（毫秒）',
    qps_limit      INT          NOT NULL DEFAULT 60 COMMENT '每分钟最大调用次数（0 = 跟随全局默认）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 发布 1 下线',
    remark         VARCHAR(255) NULL COMMENT '备注',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY ux_tenant_path (tenant_id, path, deleted),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB COMMENT ='数据服务 API 定义';

-- ---------------- 数据服务调用方应用 ----------------
CREATE TABLE IF NOT EXISTS dat_api_app
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    name           VARCHAR(100) NOT NULL COMMENT '应用名称',
    app_key        VARCHAR(64)  NOT NULL COMMENT '全局唯一',
    app_secret     VARCHAR(255) NOT NULL COMMENT '密文（AES-GCM）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark         VARCHAR(255) NULL COMMENT '备注',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY ux_app_key (app_key, deleted),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB COMMENT ='数据服务调用方应用';

-- ---------------- API 授权 ----------------
CREATE TABLE IF NOT EXISTS dat_api_grant
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    api_id         BIGINT       NOT NULL COMMENT '数据服务 API ID',
    app_id         BIGINT       NOT NULL COMMENT 'dat_api_app.id',
    expire_time    DATETIME     NULL COMMENT '过期时间（空=永久）',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY ux_api_app (api_id, app_id, deleted),
    KEY idx_tenant_app (tenant_id, app_id)
) ENGINE = InnoDB COMMENT ='数据服务 API 授权';

-- ---------------- API 调用日志 ----------------
CREATE TABLE IF NOT EXISTS dat_api_log
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    api_id         BIGINT       NOT NULL COMMENT '数据服务 API ID',
    app_id         BIGINT       NOT NULL COMMENT '调用方应用 ID',
    params         VARCHAR(2000) NULL COMMENT '本次请求参数 JSON',
    row_count      INT          NULL COMMENT '返回行数',
    cost_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    status         VARCHAR(10)  NOT NULL COMMENT 'success / failed',
    error_msg      VARCHAR(1000) NULL COMMENT '错误信息',
    client_ip      VARCHAR(64)  NULL COMMENT '客户端 IP',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_api (tenant_id, api_id, create_time),
    KEY idx_tenant_app (tenant_id, app_id, create_time)
) ENGINE = InnoDB COMMENT ='数据服务 API 调用日志';

-- ---------------- 任务级血缘 ----------------
CREATE TABLE IF NOT EXISTS dat_lineage
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    job_id         BIGINT       NOT NULL COMMENT '同步任务',
    job_name       VARCHAR(100) NULL COMMENT '任务名称',
    datasource_id  BIGINT       NOT NULL COMMENT '来源数据源',
    source_type    VARCHAR(10)  NOT NULL DEFAULT 'table' COMMENT 'table / sql',
    source_table   VARCHAR(200) NULL COMMENT '来源表（sql 来源存查询摘要）',
    target_table   VARCHAR(100) NOT NULL COMMENT '目标 ODS 表',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY ux_tenant_job (tenant_id, job_id, deleted),
    KEY idx_tenant_target (tenant_id, target_table),
    KEY idx_tenant_source (tenant_id, source_table(100))
) ENGINE = InnoDB COMMENT ='任务级血缘（同步任务：源表 → ODS）';

-- ---------------- 数据质量规则 ----------------
CREATE TABLE IF NOT EXISTS dat_quality_rule
(
    id             BIGINT        NOT NULL COMMENT '主键ID',
    tenant_id      BIGINT        NOT NULL DEFAULT 0 COMMENT '租户编号',
    name           VARCHAR(100)  NOT NULL COMMENT '规则名称',
    datasource_id  BIGINT        NOT NULL DEFAULT 0 COMMENT '0 = 中台库贴源层',
    target_table   VARCHAR(100)  NOT NULL COMMENT '目标表',
    rule_type      VARCHAR(20)   NOT NULL COMMENT 'not_null/unique/range/enum/custom_sql/row_count_fluctuation',
    column_name    VARCHAR(100)  NULL COMMENT '检测列（custom_sql/fluctuation 无需）',
    rule_config    VARCHAR(1000) NULL COMMENT '规则配置 JSON（min/max/values/sql/thresholdPct）',
    cron           VARCHAR(40)   NULL COMMENT '调度 cron（空=仅手动）',
    status         TINYINT       NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    last_run_time  DATETIME      NULL COMMENT '最近执行时间',
    last_pass      TINYINT       NULL COMMENT '最近结果：0 未通过 1 通过',
    last_pass_rate DECIMAL(5, 2) NULL COMMENT '最近通过率（%）',
    remark         VARCHAR(255)  NULL COMMENT '备注',
    create_by      BIGINT        NULL COMMENT '创建者',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT        NULL COMMENT '更新者',
    update_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB COMMENT ='数据质量规则';

-- ---------------- 数据质量报告 ----------------
CREATE TABLE IF NOT EXISTS dat_quality_report
(
    id           BIGINT        NOT NULL COMMENT '主键ID',
    tenant_id    BIGINT        NOT NULL DEFAULT 0 COMMENT '租户编号',
    rule_id      BIGINT        NOT NULL COMMENT '质量规则 ID',
    rule_name    VARCHAR(100)  NULL COMMENT '规则名冗余',
    status       VARCHAR(10)   NOT NULL COMMENT 'success / failed',
    total_rows   BIGINT        NULL COMMENT '检测总行数',
    bad_rows     BIGINT        NULL COMMENT '坏数据行数',
    pass_rate    DECIMAL(5, 2) NULL COMMENT '通过率（%）',
    cost_ms      BIGINT        NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    error_msg    VARCHAR(1000) NULL COMMENT '错误信息',
    trigger_type VARCHAR(10)   NOT NULL DEFAULT 'manual' COMMENT 'manual / sched',
    start_time   DATETIME      NOT NULL COMMENT '开始时间',
    end_time     DATETIME      NULL COMMENT '结束时间',
    create_by    BIGINT        NULL COMMENT '创建者',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    BIGINT        NULL COMMENT '更新者',
    update_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_rule (tenant_id, rule_id, start_time)
) ENGINE = InnoDB COMMENT ='数据质量报告';

-- ---------------- API 审批记录 ----------------
CREATE TABLE IF NOT EXISTS dat_api_approval
(
    id            BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    api_id        BIGINT       NOT NULL COMMENT '数据服务 API ID',
    api_name      VARCHAR(100) NULL COMMENT 'API 名称',
    action        VARCHAR(10)  NOT NULL COMMENT 'submit/approve/reject/offline',
    comment       VARCHAR(500) NULL COMMENT '审批意见',
    operator_id   BIGINT       NULL COMMENT '操作人 ID',
    operator_name VARCHAR(64)  NULL COMMENT '操作人姓名',
    create_by     BIGINT       NULL COMMENT '创建者',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by     BIGINT       NULL COMMENT '更新者',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_api (tenant_id, api_id, create_time)
) ENGINE = InnoDB COMMENT ='数据服务 API 审批记录';

-- 管理端权限点（9501-9520）已迁移至 data_base.sql（对基座库执行；sys_permission 为基座库全局表）

-- ---------------- 中台库通道查询白名单 ----------------
-- datasource_id=0 的查询放行 ods_ 前缀贴源表 + 本表白名单业务表（两类表均自动注入租户条件）；
-- 独立库租户的白名单业务表在执行期路由至其租户专属库
CREATE TABLE IF NOT EXISTS dat_query_allowlist
(
    id           BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    table_name   VARCHAR(100) NOT NULL COMMENT '放行的表名（小写归一化）',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark       VARCHAR(255) NULL COMMENT '备注',
    create_by    BIGINT       NULL COMMENT '创建者',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    BIGINT       NULL COMMENT '更新者',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY ux_tenant_table (tenant_id, table_name, deleted)
) ENGINE = InnoDB COMMENT ='中台库通道查询白名单（ods_ 之外可查业务表）';
