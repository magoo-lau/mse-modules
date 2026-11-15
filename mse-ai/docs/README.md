# mse-ai —— AI 智能体平台（产品规划）

> **状态：已落地**（基于 Spring AI 2.x，BOM 仲裁 2.0.1）。本文档集为产品规划留档，实际落库以工程内版本化脚本为准（`ai-service/src/main/resources/db/mysql/`）。
> 本文档集参照 mse-mes 文档集风格编写：01/02/03 逐条转录种子脚本真实 id，04 面向最终用户，05 面向实施。

## 一、产品定位

面向**多租户 SaaS** 的 AI 智能体平台：以微服务 `mse-ai` 承载平台级 AI 能力——模型中心 / 对话 / 智能体（含画布编排）/ 工具中心 / 知识库 RAG / MCP / 开放 API，其他服务与第三方系统按积木方式消费。

**双层能力**（本仓库中的形态）：

| 层 | 模块 | 用途 |
| --- | --- | --- |
| 技术积木 | `mse-framework/mse-common/common-ai`（框架区） | 轻量内嵌：业务服务自引模型 starter + `spring.ai.*` 配置，本地拿一个 `ChatClient`（`mseChatClient`，无模型配置时零副作用），不经 mse-ai |
| 业务平台 | `mse-modules/mse-ai`（本工程） | 完整 Agent 平台：模型 / 对话 / 智能体 / 工具 / 知识库 / MCP / 开放 API，**多服务共享** |

在本仓库中的形态：**mse-modules 业务工程**（独立 Maven 工程，不进根 pom 聚合，父 pom 指向 `mse-starter-parent`），wujie 微前端子应用 + 微服务启动器：

- `ai-api`：跨服务 RPC 契约（正向 `RemoteAiChatService` / `RemoteAiAgentService`，反向 `RemoteAiTool` / `RemoteAiToolRegistry`）
- `ai-service`：业务积木（SPI 自装配，Controller / Service / Mapper 随 jar 就绪；单体聚合可复用）
- `ai-boot`：微服务启动器（micro-starter + ai-service + MCP Server + GraalJS，`vip.mseapp.boot.ai`）
- `ai-ui`：子应用前端（Vue3 + Element Plus 紧凑型，wujie 嵌入基座，9 页全租户端）

**平台端 / 租户端双世界**：`tenant_id` 即世界编号（0=平台世界）。全部 ai_* 表带 tenant_id 行级隔离；智能体 / 模型 / 工具 / MCP / API Key **全面租户自管**——平台不再维护全局公共能力，出厂种子经「每租户播种」通道复制为各租户 scope=tenant 自有副本，租户直接编辑 / 发布 / 删除（详见 [03-角色规划](03-角色规划.md) 第五节）。

## 二、文档导读

| 分册 | 内容 | 读者 |
| --- | --- | --- |
| [01-功能规划.md](01-功能规划.md) | 功能模块与功能点清单、核心业务闭环、AI 平台对外开放 / 被消费方式专节 | 产品 / 全员 |
| [02-权限规划.md](02-权限规划.md) | 子应用注册、页面菜单（sys_menu）与权限点（sys_permission）**真实 id 全量清单**、编码规范 | 后端 / 前端 |
| [03-角色规划.md](03-角色规划.md) | 角色清单（建议角色，工程无角色种子）、角色 × 模块授权矩阵、双世界数据权限、种子落库情况 | 后端 / 实施 |
| [04-操作说明书/](04-操作说明书/README.md) | **分角色操作说明书**（总览 + 3 个岗位手册：平台管理员 / 租户 AI 管理员 / 普通用户），含通用操作与常见问题 | 最终用户 / 培训交付 |
| [05-配置手册-零基础版.md](05-配置手册-零基础版.md) | **面向门外汉的部署配置指南**：名词卡、单体 / 微服务部署、模型通道配置、RAG 开启、租户初始化、排查表与上线清单 | 实施 / 现场 IT |

阅读顺序：01 → 02 → 03。**04 为面向最终用户的交付文档、05 为面向实施的零基础部署文档**，均以系统实际界面与配置为准。

## 三、资源分配（实际占用值，登记见 alloc-tables.md）

> 以下均为工程**实际占用值**，与 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md` 登记一致。

| 资源 | 实际值 | 说明 |
| --- | --- | --- |
| 后端端口 `server.port` | **8094** | 微服务形态 ai-boot（Nacos `mse-ai.yaml`） |
| 前端 dev 端口 | **3800** | ai-ui vite dev server |
| 子应用注册 `sys_micro_app.id` | **7** | name=`ai`，标题「AI 服务」，side=3（双端兜底），dev `//localhost:3800`、生产 `/sub-apps/ai/` |
| 页面菜单 `sys_menu.id` | **460-468** | 460=type=1 分组目录；461-468 页面（469「模型配置」已随模型租户化退役） |
| 权限点 `sys_permission.id` | **8001-8021**（现存 20 个） | 8005（ai:agent:platform）/ 8022、8023（ai:model:tenant:*）已随智能体 / 模型租户化退役 |
| 权限码前缀 | `ai:` | 格式 `ai:<资源>:<动作>`，与 oa/mp/fs 等工程同风格 |
| 网关路由前缀 | `/ai`、`/openapi/ai` | `lb://mse-ai`；白名单 `/openapi/ai/**`（开放 API 免登录鉴权走 X-App-Id + X-Api-Key） |
| 智能体种子 `ai_agent.id` | **8001-8006** | 业务表行 id（与 sys_permission 8001-8006 分属不同表，无冲突） |
| 工具种子 `ai_tool.id` | **8101-8102** | oa_flow（group=mse-oa）/ data_query（group=mse-data），source=rpc |
| sys_config 参数种子 | 无 | mse-ai 自身零 sys_config 参数；AI 能力开关由各消费工程自带（如 `oa.ai.mode`=53100、`mes.ai.mode`=53200） |
| 微服务应用名 | `mse-ai` | spring.application.name；`@TenantServiceModule("mse-ai")` |

## 四、工程结构蓝图

```
mse-ai/
├── pom.xml                  # 父 pom 指向 mse-starter-parent（relativePath 置空）
├── README.md                # 工程主文档（技术权威）
├── docs/                    # 本文档集（产品规划）
├── ai-api/                  # RPC 契约：正向 RemoteAiChatService / RemoteAiAgentService
│   │                        #   反向 RemoteAiTool + 自动登记推送器（AiToolRegistrationPusher）
├── ai-service/              # 业务积木（SPI 自装配；controller/service/entity/mapper/open/openapi）
│   └── src/main/resources/db/mysql/
│       ├── V20261001_01_ai__ai_base.sql      # 注册+菜单+权限点（mse-target: base，starter-bridge 自动推送）
│       ├── V20261001_02_ai__ai_init.sql      # 业务表+智能体/工具种子（mse-target: master）
│       └── V20261001_03_ai__tenant_seed.sql  # 智能体/模型/工具每租户播种副本（master + per-tenant）
├── ai-boot/                 # 启动器：micro-starter + ai-service + MCP Server + GraalJS（vip.mseapp.boot.ai，8094）
├── ai-ui/                   # 子应用（dev 3800；构建 --base=/sub-apps/ai/；路由 /chat /agent /kb /usage /tool /platform/*）
└── docker/
    ├── nacos/config/mse-ai.yaml   # 服务专属配置留档（端口、mse.ai.* 等，经 init-configs.sh 导入 Nacos）
    └── milvus/compose.yaml        # 知识库 RAG 向量库（Milvus 2.6 standalone，可选）
```

部署形态：微服务为默认形态（Nacos + Dubbo，网关 `/ai` 与 `/openapi/ai` 前缀）；单体聚合仅需 `mse-monolith` 引入 `ai-service` jar（pom 已内置，`mse.rpc.mode=local` 本地直调，消费方零改动）。

## 五、平台协作约定（协作红线）

1. **不改框架区**：一切定制经 SPI 自动装配 + `@ConditionalOnMissingBean` 替换语义实现。
2. **SQL 免手工执行**：业务表脚本（`mse-target: master`）随启动自动迁移；基座注册（`mse-target: base`）由 starter-bridge 自动推送；租户副本（`mse-per-tenant: true`）随租户开通播种。
3. **权限点一律挂靠本工程 type=3 页面菜单**，参与角色授权分组与租户套餐裁剪；前端按钮用 `v-permission` 按权限码控制。
4. **消费方接入走契约不走 HTTP 内部端点**：正向 `ai-api` 的 `RemoteAiChatService`（同步）/ 前端 `/ai/chat/stream`（SSE 流式）；反向业务服务实现 `RemoteAiTool` 自动推送登记——**业务工程不自建模型通道**（能开关与场景映射由消费方 sys_config 参数 + `RemoteAiAgentService` 下拉承载，如 oa / mes 的「AI 能力模式」）。
5. **AI 红线**：智能体只有「已发布」状态才进入对话 / RPC / 开放通道；AI 输出一律标注「AI 生成，仅供参考」，写操作由消费方业务侧人工确认；绝不让模型直连业务库——数据供给全部经工具中心受控通道（如 `data_query` 受控只读 SQL）。
