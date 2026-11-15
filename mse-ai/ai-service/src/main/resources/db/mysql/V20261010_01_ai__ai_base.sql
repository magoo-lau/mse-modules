-- mse-target: master
-- =====================================================================
-- 本文件为 ai 模块最终态基线，由 V20261001_01~V20261005_01 合并而来，中间演进（加列/种子退役）已内联为最终形态
-- AI 服务初始化脚本（独立工程 mse-modules/mse-ai，MySQL）
-- 目标库：业务主库 + 各租户独立库（随服务启动/懒初始化按账本应用）
-- 内容：ai_* 业务表最终结构 + master 种子最终集
-- 合并说明：①ai_agent 建表即含登记来源双列 source/rpc_group（原 V20261001_04 条件加列已内联）；
--       ②业务智能体平台种子 8002-8007（oa_approve_assist/oa_process_designer/oa_form_fill/
--       oa_flow_bot/data_analyst/biz_analyst）已退役——改由业务积木 AiAgentProvider 声明登记
--       （单体启动扫描 source=local / 微服务推送 source=rpc，提示词以提供方为权威幂等刷新，
--       含 biz_analyst「空结果即停」终版提示词，原 V20261003_02 升级 UPDATE 不再需要），
--       本基线不再播种，缺副本租户经可见性/对话入口懒补齐；③仅保留 mse-ai 自家 builtin
--       general（8001，source=builtin，无提供方）与 rpc 工具登记 8101/8102/8103（工具不在退役范围）；
--       ④ai_model_tenant / ai_model_tenant_config 两张 legacy 表机制已退役（代码零引用，
--       平台库份 V20261002_01 起即不再建），本基线不再建表，存量库残留无害。
-- 配套：tenant_seed.sql（每租户播种副本，V20261010_02）；平台库份（V20261010_03）；
--       子应用/菜单/权限点（V20261010_04）
-- 幂等：建表 IF NOT EXISTS、种子 NOT EXISTS 判重，可重复执行
-- 执行：自动——随服务启动应用主库（mse.tenant.schema.auto-migrate 默认开启）；租户独立库经「租户数据源初始化/懒初始化」克隆+迁移自动就绪
-- 结构要点：真租户化终态——ai_agent 唯一键 uk_agent_tenant(tenant_id, agent_code, deleted)、
--       ai_tool 唯一键 uk_tool_world_code(tenant_id, tool_code, deleted)（编码世界内唯一，平台种子行
--       经 tenant_seed 按租户播种副本）；知识库带切片索引增强配置列、切片带自动关键词/问题列
--
-- 多租户：全部 ai_* 表带 tenant_id（0 = 平台公共数据），行级隔离由基座租户插件统一接管；
--         ai_chat_session/ai_chat_message/ai_usage_log/ai_tool_log/ai_knowledge_base/
--         ai_kb_document/ai_kb_chunk 另标注 @TenantDatasource（独立库租户自动路由专属库）；
--         平台公共行（ai_model/ai_tool/ai_mcp_server/平台智能体等）由服务层 runAsPlatform 读取
-- =====================================================================

-- ---------------- 模型配置（平台级全局表） ----------------
CREATE TABLE IF NOT EXISTS ai_model
(
    id                    BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台公共数据）',
    name                  VARCHAR(100) NOT NULL COMMENT '模型名称',
    provider              VARCHAR(30)  NULL COMMENT '供应商标注（deepseek/zhipu/moonshot/dashscope/openai/anthropic/ollama/xfyun/openai-compat，仅展示与预设联动）',
    protocol              VARCHAR(20)  NOT NULL DEFAULT 'openai-chat' COMMENT '接口类型（openai-chat=OpenAI兼容 chat/completions；anthropic-messages=Claude 原生 /v1/messages；向量模型行固定 openai-chat）',
    model_type            VARCHAR(20)  NOT NULL DEFAULT 'chat' COMMENT '模型类型（chat=对话模型 embedding=向量模型 rerank=重排模型，一行只表达一种能力）',
    is_default            TINYINT      NOT NULL DEFAULT 0 COMMENT '默认向量模型标记（仅 model_type=embedding 有意义，全局唯一）',
    base_url              VARCHAR(255) NOT NULL COMMENT '模型端点（openai-chat 自带版本前缀如 https://api.deepseek.com；anthropic-messages 如 https://api.anthropic.com）',
    api_key_enc           VARCHAR(255) NULL COMMENT 'API Key 密文（AES-GCM）',
    chat_model            VARCHAR(100) NOT NULL COMMENT '对话模型名（model_type=embedding 时为空串）',
    embedding_model       VARCHAR(100) NULL COMMENT '向量模型名（model_type=embedding 时为本行向量模型名；chat 行为旧版遗留字段，仅旧数据兼容读取）',
    embedding_base_url    VARCHAR(255) NULL COMMENT '向量模型独立端点（空复用 base_url）',
    embedding_api_key_enc VARCHAR(255) NULL COMMENT '向量模型独立 API Key 密文（空复用 api_key_enc）',
    temperature           DOUBLE       NULL COMMENT '默认温度',
    top_p                 DOUBLE       NULL COMMENT '默认 topP',
    max_tokens            INT          NULL COMMENT '默认最大输出 tokens',
    failover_model_id     BIGINT       NULL COMMENT '备用对话模型（ai_model.id，model_type=chat；主模型瞬态错误重试仍失败时自动切换，空=不切换）',
    status                TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    last_test_time        DATETIME     NULL COMMENT '最近连通性测试时间',
    last_test_ok          TINYINT      NULL COMMENT '最近测试结果：0 失败 1 成功',
    last_test_msg         VARCHAR(500) NULL COMMENT '最近测试失败原因',
    remark                VARCHAR(255) NULL COMMENT '备注',
    create_by             BIGINT       NULL COMMENT '创建者',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by             BIGINT       NULL COMMENT '更新者',
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT ='模型配置（平台公共 tenant_id=0）';

-- ---------------- 智能体（平台级全局表；scope=platform 公共 / tenant 租户自有） ----------------
CREATE TABLE IF NOT EXISTS ai_agent
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    agent_code     VARCHAR(64)  NOT NULL COMMENT '智能体编码（世界内唯一；平台种子行经 tenant_seed 播种租户副本）',
    name           VARCHAR(100) NOT NULL COMMENT '名称',
    avatar         VARCHAR(255) NULL COMMENT '头像（emoji 或 URL）',
    description    VARCHAR(500) NULL COMMENT '描述',
    system_prompt  TEXT         NULL COMMENT '系统提示词（支持 {{var}} 占位）',
    model_id       BIGINT       NULL COMMENT '绑定模型（ai_model.id，空=平台默认模型）',
    temperature    DOUBLE       NULL COMMENT '温度覆盖',
    welcome_message VARCHAR(500) NULL COMMENT '开场白',
    tool_codes     VARCHAR(500) NULL COMMENT '工具编码列表（逗号分隔）',
    kb_ids         VARCHAR(200) NULL COMMENT '知识库 ID 列表（逗号分隔）',
    orchestration_type VARCHAR(16) NOT NULL DEFAULT 'simple' COMMENT '编排模式：simple 简单配置 / flow 画布编排',
    graph_json     LONGTEXT     NULL COMMENT '画布编排 DSL（mse-flow/v1 JSON，flow 模式生效）',
    draft_graph_json LONGTEXT   NULL COMMENT '画布编排草稿（编辑器保存目标，发布时校验并拷贝到 graph_json）',
    scope          VARCHAR(10)  NOT NULL DEFAULT 'tenant' COMMENT 'platform 平台公共 / tenant 租户自有',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '归属租户（platform 时为 0）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 已发布 1 下线',
    source         VARCHAR(16)  NOT NULL DEFAULT 'custom' COMMENT '来源：builtin 平台种子 / local 单体声明登记 / rpc 微服务推送登记 / custom 页面自建',
    rpc_group      VARCHAR(64)  NULL COMMENT '声明提供方服务名（source=rpc 时登记，防同码互相覆盖）',
    remark         VARCHAR(255) NULL COMMENT '备注',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tenant (tenant_id, agent_code, deleted),
    KEY idx_scope (scope, tenant_id)
) ENGINE = InnoDB COMMENT ='智能体（平台级）';

-- ---------------- 对话会话（租户业务表，@TenantDatasource） ----------------
CREATE TABLE IF NOT EXISTS ai_chat_session
(
    id               BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    title            VARCHAR(100) NULL COMMENT '会话标题',
    agent_code       VARCHAR(64)  NOT NULL COMMENT '智能体编码',
    user_id          BIGINT       NOT NULL COMMENT '所属用户',
    last_active_time DATETIME     NULL COMMENT '最近活跃时间',
    create_by        BIGINT       NULL COMMENT '创建者',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        BIGINT       NULL COMMENT '更新者',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_user (tenant_id, user_id),
    KEY idx_agent (agent_code)
) ENGINE = InnoDB COMMENT ='对话会话';

-- ---------------- 对话消息（租户业务表；Spring AI ChatMemory 持久化实现） ----------------
CREATE TABLE IF NOT EXISTS ai_chat_message
(
    id         BIGINT      NOT NULL COMMENT '主键ID',
    tenant_id  BIGINT      NOT NULL DEFAULT 0 COMMENT '租户编号',
    session_id VARCHAR(32) NOT NULL COMMENT '会话 ID（ai_chat_session.id 字符串）',
    role       VARCHAR(20) NOT NULL COMMENT 'user / assistant',
    content    MEDIUMTEXT  NOT NULL COMMENT '消息内容',
    ext        MEDIUMTEXT  NULL COMMENT '扩展信息 JSON（assistant 消息：datasets 工具数据结果 / sources 引用来源，历史回放恢复用）',
    create_by  BIGINT      NULL COMMENT '创建者',
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by  BIGINT      NULL COMMENT '更新者',
    update_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted    TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_session (session_id)
) ENGINE = InnoDB COMMENT ='对话消息';

-- ---------------- 用量流水（租户业务表；gateway/rpc/openapi 三通道统一记录） ----------------
CREATE TABLE IF NOT EXISTS ai_usage_log
(
    id                BIGINT      NOT NULL COMMENT '主键ID',
    tenant_id         BIGINT      NOT NULL DEFAULT 0 COMMENT '租户编号',
    channel           VARCHAR(20) NOT NULL COMMENT 'gateway / rpc / openapi',
    agent_code        VARCHAR(64) NULL COMMENT '智能体编码',
    model             VARCHAR(100) NULL COMMENT '模型名',
    conversation_id   VARCHAR(64) NULL COMMENT '会话 ID',
    user_id           BIGINT      NULL COMMENT '触发用户（openapi 通道为空）',
    prompt_tokens     INT         NULL COMMENT '提示词 tokens 消耗',
    completion_tokens INT         NULL COMMENT '补全 tokens 消耗',
    total_tokens      INT         NULL COMMENT '总 tokens 消耗',
    duration_ms       BIGINT      NULL COMMENT '耗时毫秒',
    status            TINYINT     NOT NULL DEFAULT 0 COMMENT '0 成功 1 失败',
    error_msg         VARCHAR(500) NULL COMMENT '失败原因',
    create_by         BIGINT      NULL COMMENT '创建者',
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by         BIGINT      NULL COMMENT '更新者',
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, create_time),
    KEY idx_agent (agent_code)
) ENGINE = InnoDB COMMENT ='AI 用量流水';

-- ---------------- 工具登记（平台公共 + 租户自有） ----------------
CREATE TABLE IF NOT EXISTS ai_tool
(
    id                    BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '归属租户（0=平台公共系统工具，>0=租户自有工具）',
    tool_code             VARCHAR(100) NOT NULL COMMENT '工具编码（世界内唯一；平台工具行经 tenant_seed 播种租户副本）',
    name                  VARCHAR(100) NULL COMMENT '工具名称',
    description           VARCHAR(500) NULL COMMENT '功能说明（面向模型）',
    parameters_json_schema TEXT        NULL COMMENT '入参 JSON Schema',
    source                VARCHAR(20)  NOT NULL DEFAULT 'rpc' COMMENT 'builtin / local / rpc / http / dify',
    rpc_group             VARCHAR(64)  NULL COMMENT 'RPC 提供方服务名（Dubbo group）',
    config_json           TEXT         NULL COMMENT '外部工具连接配置（source=http/dify 时生效）',
    status                TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark                VARCHAR(255) NULL COMMENT '备注',
    create_by             BIGINT       NULL COMMENT '创建者',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by             BIGINT       NULL COMMENT '更新者',
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_world_code (tenant_id, tool_code, deleted)
) ENGINE = InnoDB COMMENT ='工具登记（tenant_id=0 平台公共系统工具，>0 租户自有）';

-- ---------------- 工具调用审计（租户业务表） ----------------
CREATE TABLE IF NOT EXISTS ai_tool_log
(
    id          BIGINT      NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '租户编号',
    tool_code   VARCHAR(100) NOT NULL COMMENT '工具编码',
    agent_code  VARCHAR(64) NULL COMMENT '智能体编码',
    session_id  VARCHAR(64) NULL COMMENT '会话 ID',
    arguments   VARCHAR(2000) NULL COMMENT '入参 JSON',
    result      VARCHAR(2000) NULL COMMENT '出参内容',
    success     TINYINT     NOT NULL DEFAULT 0 COMMENT '0 成功 1 失败',
    duration_ms BIGINT      NULL COMMENT '耗时毫秒',
    error_msg   VARCHAR(500) NULL COMMENT '失败原因',
    create_by   BIGINT      NULL COMMENT '创建者',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT      NULL COMMENT '更新者',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant_tool (tenant_id, tool_code)
) ENGINE = InnoDB COMMENT ='工具调用审计';

-- ---------------- 知识库（租户业务表） ----------------
CREATE TABLE IF NOT EXISTS ai_knowledge_base
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    name        VARCHAR(100) NOT NULL COMMENT '名称',
    description VARCHAR(500) NULL COMMENT '描述',
    chunk_size  INT          NULL COMMENT '切片目标大小（token）',
    chunk_method VARCHAR(20) NOT NULL DEFAULT 'general' COMMENT '切片方法（general/qa/table/book/one）',
    chunk_overlap INT        NULL DEFAULT 100 COMMENT '切片重叠大小（token，general/book 生效）',
    top_k       INT          NULL COMMENT '默认检索条数',
    similarity_threshold DECIMAL(4,3) NULL DEFAULT 0.500 COMMENT '相似度阈值（0~1）',
    embedding_model_id BIGINT   NULL COMMENT '绑定向量模型（ai_model.id，model_type=embedding；空=按旧版全局配置/平台默认向量模型解析）',
    rerank_model_id     BIGINT   NULL COMMENT '绑定重排模型（ai_model.id，model_type=rerank；空=按旧版平台级配置解析，均未配置则不重排）',
    enhance_enabled  TINYINT     NOT NULL DEFAULT 0 COMMENT '切片索引增强（LLM 自动关键词/自动问题）：0 关 1 开',
    enhance_questions INT        NOT NULL DEFAULT 3 COMMENT '索引增强每切片自动问题数（1~5）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark      VARCHAR(255) NULL COMMENT '备注',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB COMMENT ='知识库';

-- ---------------- 知识库文档（租户业务表） ----------------
CREATE TABLE IF NOT EXISTS ai_kb_document
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    kb_id       BIGINT       NOT NULL COMMENT '知识库 ID',
    file_name   VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type   VARCHAR(20)  NULL COMMENT '文件类型',
    size_bytes  BIGINT       NULL COMMENT '文件大小',
    storage_path VARCHAR(500) NULL COMMENT '原文件归档路径（支撑重新解析/重建索引）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 待处理 1 处理中 2 完成 3 失败',
    chunk_count INT          NULL COMMENT '切片数量',
    error_msg   VARCHAR(500) NULL COMMENT '失败原因',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_kb (kb_id)
) ENGINE = InnoDB COMMENT ='知识库文档';

-- ---------------- 知识库切片（租户业务表；id 即向量库 Document id） ----------------
CREATE TABLE IF NOT EXISTS ai_kb_chunk
(
    id          BIGINT      NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '租户编号',
    kb_id       BIGINT      NOT NULL COMMENT '知识库 ID',
    document_id BIGINT      NOT NULL COMMENT '文档 ID',
    seq         INT         NOT NULL DEFAULT 0 COMMENT '切片序号',
    content     MEDIUMTEXT  NOT NULL COMMENT '切片正文',
    keywords    VARCHAR(1024) NULL COMMENT '索引增强·自动关键词（JSON 数组串；空=未增强）',
    questions   VARCHAR(2000) NULL COMMENT '索引增强·自动问题（JSON 数组串；重建索引复嵌）',
    create_by   BIGINT      NULL COMMENT '创建者',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT      NULL COMMENT '更新者',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_kb_doc (kb_id, document_id)
) ENGINE = InnoDB COMMENT ='知识库切片';

-- ---------------- 开放 API 密钥（平台级全局表；key 明文仅签发时返回一次） ----------------
CREATE TABLE IF NOT EXISTS ai_api_key
(
    id             BIGINT       NOT NULL COMMENT '主键ID',
    app_id         VARCHAR(64)  NOT NULL COMMENT '应用 ID',
    key_hash       VARCHAR(64)  NOT NULL COMMENT 'API Key 哈希（SHA-256）',
    key_prefix     VARCHAR(16)  NULL COMMENT 'Key 前缀（识别用）',
    tenant_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '业务归属租户',
    agent_codes    VARCHAR(500) NULL COMMENT '智能体白名单（空=全部已发布）',
    rate_per_minute INT         NULL COMMENT '每分钟调用上限（0/空=不限）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark         VARCHAR(255) NULL COMMENT '备注',
    create_by      BIGINT       NULL COMMENT '创建者',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by      BIGINT       NULL COMMENT '更新者',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_app (app_id)
) ENGINE = InnoDB COMMENT ='开放 API 密钥（平台级）';

-- ---------------- 外部 MCP Server 注册（平台级全局表） ----------------
CREATE TABLE IF NOT EXISTS ai_mcp_server
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号（0=平台公共数据）',
    name        VARCHAR(64)  NOT NULL COMMENT '服务名（唯一，工具编码引用）',
    endpoint    VARCHAR(255) NOT NULL COMMENT '基础地址',
    sse_path    VARCHAR(100) NULL COMMENT 'SSE 路径（默认 /sse）',
    transport   VARCHAR(20)  NOT NULL DEFAULT 'sse' COMMENT '传输方式',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    remark      VARCHAR(255) NULL COMMENT '备注',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_name (name)
) ENGINE = InnoDB COMMENT ='外部 MCP Server 注册（平台公共 tenant_id=0）';

-- ---------------- 工作流运行历史（每次编排执行收口一条） ----------------
CREATE TABLE IF NOT EXISTS ai_workflow_run
(
    id           BIGINT       NOT NULL COMMENT '主键ID',
    agent_code   VARCHAR(64)  NOT NULL COMMENT '智能体编码',
    agent_name   VARCHAR(100) NULL COMMENT '智能体名称快照',
    channel      VARCHAR(16)  NULL COMMENT '触发通道：gateway 对话 / debug 试运行 / rpc 无状态 / openapi 开放接口',
    session_id   VARCHAR(64)  NULL COMMENT '会话号（debug 无会话为空）',
    status       VARCHAR(16)  NOT NULL COMMENT '运行结果：success / failed / cancelled',
    query_text   VARCHAR(1000) NULL COMMENT '用户输入（截断存档）',
    answer       TEXT         NULL COMMENT '最终回答（截断存档）',
    error        VARCHAR(500) NULL COMMENT '失败原因',
    elapsed_ms   BIGINT       NULL COMMENT '总耗时毫秒',
    node_total   INT          NULL COMMENT '节点轨迹条数',
    traces_json  LONGTEXT     NULL COMMENT '节点轨迹快照 JSON（与 SSE node 事件同构）',
    tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '归属租户（平台调试行为 0）',
    create_by    BIGINT       NULL COMMENT '创建者',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    BIGINT       NULL COMMENT '更新者',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    KEY idx_agent (agent_code, create_time),
    KEY idx_tenant (tenant_id)
) ENGINE = InnoDB COMMENT ='工作流运行历史';

-- =====================================================================
-- master 种子最终集
-- 智能体：general（8001，mse-ai 自家 builtin，无提供方；source=builtin）
-- 工具登记（source=rpc，group=提供方服务名；dubbo 模式生效；不在退役范围）：
--         oa_flow（8101，mse-oa）/ data_query（8102，mse-data）/ biz_query（8103，mse-data）
-- 说明：schema 与参数描述以提供方 describe() 为权威，服务启动扫描/周期重推会自动同步本行（保留启停与备注）
-- =====================================================================
-- ---------------- 示例数据：平台公共智能体（模型为空=回退 spring.ai.* 默认模型或首个可用模型） ----------------
INSERT INTO ai_agent (id, agent_code, name, avatar, description, system_prompt, source, scope, tenant_id, status, create_time, update_time)
SELECT 8001, 'general', '通用助手', '🤖', '平台公共通用对话助手，开箱可用',
       '你是 mse-ai 平台的通用助手。回答准确、简洁，必要时分点说明；不知道的事情不要编造。',
       'builtin', 'platform', 0, 0, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM ai_agent WHERE agent_code = 'general');

-- ---------------- 反向工具登记：oa_flow（mse-oa） ----------------
INSERT INTO ai_tool (id, tool_code, name, description, parameters_json_schema, source, rpc_group, status, create_time, update_time)
SELECT 8101, 'oa_flow', 'OA 流程查询与操作',
       '运行期：查询当前用户的待办/已办/我发起/抄送列表、流程实例详情（含表单数据与审批记录）、已发布流程模板目录、审批统计，代发起人催办。设计期（流程设计供数）：查组织部门树、角色清单、部门人员、部门负责人、按姓名搜人、查看现有流程的编排 JSON。',
       '{"type":"object","properties":{"action":{"type":"string","enum":["todo_query","instance_detail","model_catalog","stats","remind","org_tree","role_list","dept_users","dept_leaders","search_users","model_detail"],"description":"todo_query=查任务列表(需scope)；instance_detail=实例详情(需instanceId)；model_catalog=流程模板目录；stats=审批统计；remind=催办(需instanceId)；org_tree=部门树(编号|名称|上级)；role_list=角色清单；dept_users=部门人员(需deptId)；dept_leaders=部门负责人(可选deptId)；search_users=按姓名搜人(需keyword)；model_detail=查看流程编排JSON(需modelId)"},"scope":{"type":"string","enum":["todo","done","mine","cc"],"description":"todo_query 的列表范围"},"instanceId":{"type":"string","description":"流程实例编号"},"opinion":{"type":"string","description":"催办留言"},"deptId":{"type":"integer","description":"dept_users/dept_leaders 的部门编号"},"includeChildren":{"type":"boolean","description":"dept_users 是否含子部门，默认 false"},"keyword":{"type":"string","description":"search_users 的姓名关键词"},"modelId":{"type":"integer","description":"model_detail 的流程模板编号"},"pageNum":{"type":"integer","description":"页码，默认 1"},"pageSize":{"type":"integer","description":"每页条数，默认 5，最大 20"}},"required":["action"]}',
       'rpc', 'mse-oa', 0, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM ai_tool WHERE tool_code = 'oa_flow');

-- ---------------- 反向工具登记：data_query（mse-data） ----------------
INSERT INTO ai_tool (id, tool_code, name, description, parameters_json_schema, source, rpc_group, status, create_time, update_time)
SELECT 8102, 'data_query', '数据中台智能查询',
       '按 action 检索数据中台的表/列元数据（meta_search），执行受控只读 SQL 查询（execute：自动注入租户条件、限行数、只允许 SELECT），或列出可用数据源（datasource_list：中台库 + 本租户已启用数据源）。写 SQL 前先用 meta_search 确认表和列名。',
       '{"type":"object","properties":{"action":{"type":"string","enum":["meta_search","execute","datasource_list"],"description":"meta_search=检索表/列元数据；execute=执行受控只读 SQL；datasource_list=列出可用数据源"},"keyword":{"type":"string","description":"meta_search 的表名/注释关键字"},"metaTableId":{"type":"integer","description":"meta_search 指定表编号时返回该表的列清单"},"datasourceId":{"type":"integer","description":"execute 的数据源编号，0 或省略=中台库（仅 ods_ 表）"},"sql":{"type":"string","description":"execute 的只读 SELECT 语句（不含分页，系统自动追加）"},"pageNum":{"type":"integer","description":"页码，默认 1"},"pageSize":{"type":"integer","description":"每页条数，默认 20，最大 200"}},"required":["action"]}',
       'rpc', 'mse-data', 0, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM ai_tool WHERE tool_code = 'data_query');

-- ---------------- 反向工具登记：biz_query（mse-data） ----------------
INSERT INTO ai_tool (id, tool_code, name, description, parameters_json_schema, source, rpc_group, status, create_time, update_time)
SELECT 8103, 'biz_query', '业务库智能查询',
       '查询业务库的生产业务表（mes_/oa_/mp_ 等业务前缀表）。先按 action=meta_search 检索业务表目录与列清单，再按 action=execute 执行受控只读 SELECT：系统自动按租户过滤（跨租户隔离）、自动剔除已删除数据（deleted=0）、自动分页限行；系统表与凭证表（sys_/ai_ 等）不可查询。写 SQL 前先用 meta_search 确认表和列名。',
       '{"type":"object","properties":{"action":{"type":"string","enum":["meta_search","execute"],"description":"meta_search=检索业务表/列元数据；execute=执行受控只读 SQL"},"keyword":{"type":"string","description":"meta_search 的表名/注释关键字"},"table":{"type":"string","description":"meta_search 指定表名时返回该表的列清单（推荐方式，表名比长编号更不易出错）"},"metaTableId":{"type":"string","description":"meta_search 指定表编号（字符串，须整体复制不得改写）时返回该表的列清单；优先用 table 表名"},"sql":{"type":"string","description":"execute 的只读 SELECT 语句（不含分页，系统自动追加）"},"pageNum":{"type":"integer","description":"页码，默认 1"},"pageSize":{"type":"integer","description":"每页条数，默认 20，最大受系统行数上限约束"}},"required":["action"]}',
       'rpc', 'mse-data', 0, NOW(), NOW()
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM ai_tool WHERE tool_code = 'biz_query');
