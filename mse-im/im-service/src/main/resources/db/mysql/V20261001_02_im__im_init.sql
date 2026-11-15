-- mse-target: master
-- =====================================================================
-- mse-im 业务表初始化（独立工程 mse-modules/mse-im，MySQL）
-- 内容：im_instance_config 实例台账（每租户一行）+ im_bot 机器人绑定 + im_msg_audit 消息外发审计
--       + im_workbench_item 聊天客户端工作台入口配置 + im_dept_group 部门群映射 + im_user_profile 用户独立资料
-- 目标库：业务主库（缺省共享租户库 mse_tenant；独立库租户随「租户数据源初始化」在各自主库直建）
-- 执行：自动——随服务启动 auto-migrate 应用（失败阻断启动），无需手工执行
-- 号段：登记见 zcode-plugin/plugins/mse-dev/references/alloc-tables.md
-- 结构要点：im_instance_config 唯一键含 deleted（销毁后可二次开通）、带 web_base_url/livekit_url；
--       im_workbench_item 为免登授权终态——凭证归口开放平台（open_app_id + 冗余 app_key，无密钥列）
-- 幂等：CREATE TABLE IF NOT EXISTS
-- =====================================================================

-- ---------------- IM 实例台账（每租户一行） ----------------
CREATE TABLE IF NOT EXISTS im_instance_config
(
    id              BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    source          VARCHAR(20)  NOT NULL DEFAULT 'TENANT_SELF_HOSTED' COMMENT '来源：TENANT_SELF_HOSTED 租户自助 / PLATFORM_HOSTED 平台开通',
    api_url         VARCHAR(200) NOT NULL COMMENT '服务端调用地址（mse-im → OpenIM admin REST）',
    ws_url          VARCHAR(200) NULL COMMENT '内网 WS 地址（预留连通性校验）',
    public_api_url  VARCHAR(200) NULL COMMENT '下发客户端 apiAddr（缺省回落 api_url）',
    public_ws_url   VARCHAR(200) NULL COMMENT '下发客户端 wsAddr',
    web_base_url    VARCHAR(200) NULL COMMENT '工作台跳转基址（mse Web 端地址，如 http://localhost:3000 或 https://app.tenant.com；缺省回落全局 mse.im.web-base-url）',
    livekit_url     VARCHAR(255) NULL COMMENT 'LiveKit 信令地址（ws(s)://host:port，空=回落全局 mse.im.livekit.url）',
    admin_user_id   VARCHAR(64)  NOT NULL DEFAULT 'imAdmin' COMMENT '实例管理员账号',
    secret_enc      VARCHAR(255) NOT NULL COMMENT '实例 secret 密文（AES-256-GCM v1: 前缀）',
    callback_token  VARCHAR(64)  NULL COMMENT 'webhook 回调路由 token（URL 内嵌）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用（停用=该租户 IM 显式关闭，不回落配置）',
    last_test_time  DATETIME     NULL COMMENT '最近连通性测试时间',
    last_test_ok    TINYINT      NULL COMMENT '最近测试结果：0 成功 1 失败（NULL 未测）',
    last_test_msg   VARCHAR(500) NULL COMMENT '最近测试消息',
    create_by       BIGINT       NULL COMMENT '创建者',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by       BIGINT       NULL COMMENT '更新者',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_instance_tenant (tenant_id, deleted)
) ENGINE = InnoDB COMMENT ='IM 实例台账';

-- ---------------- IM 机器人绑定（bot 账号 ↔ mse-ai 智能体） ----------------
CREATE TABLE IF NOT EXISTS im_bot
(
    id               BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    bot_user_id      VARCHAR(64)  NOT NULL COMMENT 'OpenIM userID（bot_ 前缀，实例内唯一）',
    nickname         VARCHAR(64)  NULL COMMENT '展示昵称（注册进 IM）',
    face_url         VARCHAR(255) NULL COMMENT '头像 URL',
    agent_code       VARCHAR(64)  NOT NULL COMMENT '绑定的智能体编码（ai_agent.agent_code）',
    trigger_keywords VARCHAR(255) NULL COMMENT '触发关键词（逗号分隔；空=群内@即触发；单聊直发始终触发）',
    group_ids        VARCHAR(500) NULL COMMENT '群白名单（逗号分隔 groupID；空=所有群）',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    create_by        BIGINT       NULL COMMENT '创建者',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        BIGINT       NULL COMMENT '更新者',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY ux_im_bot_user (tenant_id, bot_user_id, deleted)
) ENGINE = InnoDB COMMENT ='IM 机器人绑定';

-- ---------------- IM 消息外发审计（webhook afterSendSingleMsg/GroupMsg 全量异步落库，机器人链路之外） ----------------
CREATE TABLE IF NOT EXISTS im_msg_audit
(
    id            BIGINT        NOT NULL COMMENT '主键ID',
    tenant_id     BIGINT        NOT NULL DEFAULT 0 COMMENT '租户编号',
    msg_id        VARCHAR(64)   NOT NULL COMMENT '消息ID（clientMsgID）',
    send_id       VARCHAR(64)   NOT NULL COMMENT '发送者 OpenIM userID',
    recv_id       VARCHAR(64)   NULL COMMENT '单聊接收者',
    group_id      VARCHAR(64)   NULL COMMENT '群 ID',
    session_type  TINYINT       NOT NULL COMMENT '1 单聊 3 群聊',
    content_type  INT           NOT NULL COMMENT '消息内容类型（101 文本 / 106 AtText 等）',
    content_text  VARCHAR(1000) NULL COMMENT '文本摘要（截断）',
    create_by     BIGINT        NULL COMMENT '创建者',
    create_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by     BIGINT        NULL COMMENT '更新者',
    update_time   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_msg_audit (tenant_id, msg_id, deleted)
) ENGINE = InnoDB COMMENT ='IM 消息外发审计';

-- ---------------- IM 聊天客户端工作台入口配置 ----------------
-- 免登授权凭证归口开放平台（mse-open open_app）：open_app_id 下拉选择本租户已有应用（auth_mode=1 必填），
-- app_key 为绑定的开放平台应用标识（保存时冗余落列）；im 不存储任何密钥，兑换校验经 RPC 到开放平台
CREATE TABLE IF NOT EXISTS im_workbench_item
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    entry_type  TINYINT      NOT NULL DEFAULT 0 COMMENT '入口类型：0=mse 页面菜单 1=外部应用',
    path        VARCHAR(500) NOT NULL COMMENT '入口地址：mse 侧路由（/sub/{micro}{path}）或外部应用完整 http(s) URL',
    title       VARCHAR(64)  NULL COMMENT '展示标题（缺省用菜单名）',
    icon        VARCHAR(255) NULL COMMENT '图标：emoji/字符，或 http(s) 图片 URL',
    group_name  VARCHAR(64)  NULL COMMENT '宫格分组名（空=按菜单树目录推导）',
    open_mode   TINYINT      NOT NULL DEFAULT 0 COMMENT '打开方式：0=内嵌 1=弹窗（新窗口）',
    auth_mode   TINYINT      NOT NULL DEFAULT 0 COMMENT '授权方式：0=直连 1=免登授权（授权码换 access_token）',
    app_key     VARCHAR(32)  NULL COMMENT '免登授权绑定的开放平台应用 app_key（保存时冗余落列，auth_mode=1 必填）',
    open_app_id BIGINT       NULL COMMENT '关联开放平台应用 id（auth_mode=1 必填，凭证归口 open_app）',
    role_ids    VARCHAR(500) NULL COMMENT '可见角色编号集合（逗号分隔；NULL/空=全员可见）',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序（小在前）',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0 启用 1 停用',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_workbench (tenant_id, path, deleted)
) ENGINE = InnoDB COMMENT ='IM 聊天客户端工作台入口配置';

-- ---------------- IM 部门群映射（群主=部门负责人，未设置回落租户管理员；群成员随部门名单对账收敛） ----------------
-- 部门删除时解散群并物理删行（防墓碑撞唯一键）
CREATE TABLE IF NOT EXISTS im_dept_group
(
    id           BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id    BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    dept_id      BIGINT       NOT NULL COMMENT '部门编号（sys_dept.id）',
    dept_name    VARCHAR(60)  NULL COMMENT '部门名（建群时快照，改名随对账刷新）',
    group_id     VARCHAR(64)  NOT NULL COMMENT 'OpenIM 群编号',
    owner_user_id BIGINT      NULL COMMENT '群主（=部门负责人 sys_user.id，未设置时回落租户管理员）',
    create_by    BIGINT       NULL COMMENT '创建者',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    BIGINT       NULL COMMENT '更新者',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_dept_group (tenant_id, dept_id, deleted),
    KEY idx_group (group_id)
) ENGINE = InnoDB COMMENT ='IM 部门群映射';

-- ---------------- IM 用户独立资料（头像；行惰性创建，重置置空不删行防墓碑撞键） ----------------
CREATE TABLE IF NOT EXISTS im_user_profile
(
    id          BIGINT       NOT NULL COMMENT '主键ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '租户编号',
    user_id     BIGINT       NOT NULL COMMENT '用户编号（sys_user.id，即 OpenIM userID）',
    face_url    VARCHAR(500) NOT NULL DEFAULT '' COMMENT 'IM 自设头像地址（站内相对路径或 http(s) 绝对地址；空串=未自设）',
    create_by   BIGINT       NULL COMMENT '创建者',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by   BIGINT       NULL COMMENT '更新者',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（0 未删除 1 已删除）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_im_user_profile (tenant_id, user_id, deleted)
) ENGINE = InnoDB COMMENT ='IM 用户独立资料（头像）';
