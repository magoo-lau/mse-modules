# mse-ai —— AI 智能体平台

基于 **Spring AI 2.x**（BOM 仲裁 2.0.1，Spring Boot 4 基线）的 AI Agent 业务系统：以微服务 `mse-ai`（8094）承载平台级 AI 能力，其他服务与第三方系统按积木方式消费。

## 定位：双层能力

| 层 | 模块 | 用途 |
| --- | --- | --- |
| 技术积木 | `mse-framework/mse-common/common-ai` | 轻量内嵌：业务服务自引模型 starter + `spring.ai.*` 配置，本地拿一个 `ChatClient`（`mseChatClient`，无模型配置时零副作用） |
| 业务平台 | `mse-modules/mse-ai` | 完整 Agent 平台：模型中心 / 对话 / 智能体 / 工具中心 / 知识库 RAG / MCP / 开放 API，**多服务共享** |

## 功能清单

- **模型管理（租户自管）**：各会话世界单边读写本世界自有模型行（存量平台公共行已经每租户播种副本，见「模型租户自管」节），**模型类型区分**：`chat` 对话模型（智能体/工作流绑定）、`embedding` 向量模型（知识库 RAG 绑定）与 `rerank` 重排模型（知识库精排绑定）分行建档、各选择点按类型过滤，向量模型可多行并存并设本世界默认（`is_default`）；**供应商预设自动带出内置端点**（DeepSeek / 智谱 GLM / Kimi / 通义千问 / OpenAI 官方 / Anthropic Claude / Ollama / 讯飞星火 / 自定义兼容），**接口类型可选**：`openai-chat`（OpenAI 兼容 chat/completions）与 `anthropic-messages`（Claude 原生 /v1/messages，兼容 DeepSeek/GLM/Kimi 的 Anthropic 兼容端点）；apiKey AES-GCM 加密落库；`AiModelFactory` 按配置动态构建并缓存 Spring AI 模型实例；连通性测试（向量模型真实 embed 一次并回显维度）（旧「圈定 ai_model_tenant + 覆盖 ai_model_tenant_config」机制已随模型租户化退役）
- **对话服务**：SSE 流式（`POST /ai/chat/stream`，事件 message/sources/tool/usage/done/error）+ 同步（`POST /ai/chat`）；会话/消息落库（`ChatMemory` 的 MySQL 实现，历史可审计可回放；`ai_chat_message.ext` 扩展列随消息存工具数据结果与引用来源，刷新/回放不丢）；提示词 `{{var}}` 渲染；用量流水 `ai_usage_log` 全通道记录；**随时停止**：对话页「停止」按钮经 AbortController 断流并调 `POST /ai/chat/stream/stop`（会话归属校验后取消，未在运行幂等返回）——简单模式服务端发送失败即 `completeWithError` 终止 SSE 并 dispose 流订阅；**编排智能体节点级取消**：运行中编排登记本地注册表，停止接口本地取消 + Redis 频道 `mse:ai:flow-stop` 跨实例广播（多实例部署下停止请求可能落在非运行实例；无 Redis 降级为仅本地），引擎在节点间隙/迭代循环/LLM 流式 delta 处轮询取消标志（流式节点 dispose 即刻中断上游 HTTP），取消节点轨迹记 `cancelled`、半截回答不写会话记忆；已知边界：非流式阻塞节点（`call()`/工具/HTTP）无法协作中断，当前节点完成后于下一检查点退出
- **智能体**：编码引用（`agent_code`，租户内唯一），绑定模型 + 系统提示词 + 工具集 + 知识库 + 温度；归属随会话世界（租户自有行 / 平台世界行），种子智能体已经每租户播种为可编辑自有副本；发布/下线
- **智能体画布编排（对标 Dify Chatflow）**：`orchestration_type=flow` 的智能体由画布 DSL（`graph_json`，mse-flow/v1 JSON）驱动——15 类节点（开始 / LLM / 知识检索 / 工具 / **数据查询** / 条件分支 / 问题分类 / 参数提取 / 代码执行 / HTTP 请求 / 模板转换 / **变量赋值** / 变量聚合 / 迭代 / 直接回复+结束），支持画布级**全局参数**（`env.<name>`，变量赋值节点可执行期改写），变量引用 `{{nodeId.var}}` + 系统变量 `sys.query/conversation/...`；前端 vue-flow 画布编辑器（`/agent/flow/:id`，节点面板/属性面板/全局参数/迭代子流程/试运行调试；`/platform/agent/flow/:id` 兼容重定向）；对话工作台与 RPC/开放 API 通道自动走工作流引擎，SSE 新增 `node` 事件（节点执行轨迹）；DSL 导入导出（自有 YAML + **Dify DSL 尽力映射导入**）；预置「数据分析助手」模板（参数提取 → 数据中台取数 → LLM 趋势/异常分析，详见下文「智能体画布编排」）
- **工具中心**（双向集成核心，**租户端页面**）：**双模式自动注册**——内置/本地 Bean 工具启动扫描自动登记、RPC 远程工具由业务服务自动推送登记（单体/微服务两形态行为一致，免手工维护，**登记来源零 SQL 种子**——V20261012_01/_02 已退役历史引导行）；**租户世界副本读取入口懒补齐**（缺副本补插、系统来源内容漂移随平台行刷新，清单与「绑定工具」下拉一致）；**外部工具手工登记**（HTTP 通用接口 / Dify 应用，租户自建仅本租户可见可用；rpc 来源不对界面开放，一律自动登记）；**MCP 外部工具**（`mcp:{server}:{tool}` 编码，本页只读展示、可测试）；每次调用落 `ai_tool_log` 审计；菜单租户端（scope=2）——角色授权 `ai:tool:list`/`ai:tool:manage` 并在套餐勾选页面后可见（2026-09-24 起平台端不再有工具中心入口，460/464 见 `V20261001_01_ai__ai_base.sql`）
- **知识库 RAG**（可选，Milvus 2.5+，对标 RAGFlow 核心能力）：
  - **切片方法模板**：`general` 通用（分隔符感知句子聚合 + overlap 重叠）/ `qa` 问答对（Q:/A: 文本或两列 CSV/Excel）/ `table` 表格（CSV/Excel 一行一片，"列名: 值"摘要）/ `book` 书籍（Markdown/中文章节标题层级）/ `one` 整篇；切片大小（token）与重叠（token）按库配置
  - **异步解析管线**：上传即返回（原文件归档 `mse.ai.kb-storage-path`），后台线程池解析 → 切片 → 向量化（状态机 0 待处理/1 处理中/2 完成/3 失败，前端轮询）；失败可重试，支持**重新解析**与知识库级**重建索引**（修改切片配置后使存量生效）
  - **混合检索**：Milvus 稠密向量 + **BM25 全文**（中文 analyzer，服务端 Function 自动生成稀疏向量）经 **RRF 融合**；重排（rerank，OpenAI 兼容 /rerank，硅基流动/Jina/bge-reranker 类）**接入模型中心**（model_type=rerank，知识库级绑定 `rerank_model_id`，解析链：KB 绑定 > 旧版 `mse.ai.rerank.*` 平台配置 > 不重排），多库绑定取第一个生效绑定，未配置/调用失败自动退化为融合分直排
  - **切片索引增强（LLM 自动关键词/自动问题，2026-09-23）**：知识库级开关 `enhance_enabled` + 问题数 `enhance_questions`（1~5，默认 3）——开启后解析入库时逐切片调用对话模型生成关键词与「用户可能会问的问题」（假设问题索引），前置拼入**稠密嵌入文本**提升问句式 query 召回；**BM25 与检索展示仍用原文**（不污染引用来源）；随切片落库（`ai_kb_chunk.keywords/questions`，JSON 数组串）并在分块管理展开查看；重建索引（重嵌入）复用落库增强列不重复调用 LLM，编辑切片正文则增强失效清空（需重新解析）；增强模型取 `mse.ai.kb.enhance-model-id`（空回退 spring.ai.* 默认模型），单切片失败自动降级按原文嵌入并熔断本文档剩余切片（不阻断解析）；开关/问题数变更后需「重建索引」对存量文档生效
  - **分块管理**：分块分页浏览（含增强展开）/ 手工新增 / 编辑 / 删除，保存即时重嵌入（人工干预修正召回）
  - **引用溯源**：智能体对话 SSE 新增 `sources` 事件，前端消息下方展示引用来源（文件名/得分/摘要），并随 assistant 消息 `ext` 列落库（历史回放恢复引用面板）；RPC 无状态通道 `AiChatResult.sources` 同口径返回（非编排智能体）；检索范围过滤 `tenantId + kbIds`（租户收口），检索参数取绑定知识库配置
  - 检索统一走 `KbRetrievalService` 管线（检索测试 / 智能体 RAG / 引用共用）；正文同步落 MySQL（`ai_kb_chunk`，切片 id = 向量主键双向追溯）
- **开放 API**：`ai_api_key`（appId + SHA-256 哈希 Key + 租户归属 + 智能体白名单 + Redis 每分钟限流），第三方系统直调 `/openapi/ai/**`；签发弹窗归属租户下拉选择、白名单从已发布智能体多选
- **MCP**：MCP Client 动态接入外部 MCP Server（`ai_mcp_server` 表 + 连通性测试）；MCP Server（开关 `mse.ai.mcp-server-enabled`）把平台能力暴露给外部 AI 客户端

## 模块结构

```
mse-ai/
├── ai-api/        # RPC 契约：RemoteAiChatService（消费 AI）+ RemoteAiTool（注册工具）+ RemoteAiAgentService（智能体选项只读查询）+ DTO
├── ai-service/    # 业务积木（SPI 自装配，单体聚合可复用）：controller/service/entity/mapper
├── ai-boot/       # 启动器 vip.mseapp.boot.ai（8094，网关 lb://mse-ai，路由 /ai/** 与 /openapi/ai/**）
├── ai-ui/         # Vue3 子应用（dev 3800，/sub/ai，构建 /sub-apps/ai/；9 页全租户端，平台端无 AI 菜单）
├── ai-service/src/main/resources/db/mysql/
│   ├── V20261001_01_ai__ai_base.sql        # 注册+菜单+权限点+字典（mse-target: base，starter-bridge 自动推送）
│   ├── V20261010_01_ai__ai_base.sql        # 业务表最终态（含切片索引增强列、ai_agent source/rpc_group）+种子（mse-target: master）
│   ├── V20261010_02_ai__tenant_seed.sql    # 智能体/模型/工具按租户播种副本（master + per-tenant）
│   ├── V20261010_03_ai__ai_platform.sql    # 平台库表最终态 + 平台种子（base + platform scope）
│   ├── V20261010_04_ai__app_menu_permission.sql # 子应用注册 + 菜单/权限点（base 租户域）
│   ├── V20261011_01_ai__biz_module.sql     # ai_biz_module 业务模块注册中心（master）
│   ├── V20261011_02_ai__biz_module_platform.sql # ai_biz_module 平台库份（base + base-scope: platform）
│   ├── V20261011_03_ai__biz_route_migrate.sql # biz_query 收归 mse-ai 自持迁移 + biz_analyst/data_analyst 副本刷新（master）
│   ├── V20261011_04_ai__biz_route_migrate_platform.sql # 同上平台库份（base + base-scope: platform）
│   ├── V20261002_01_ai__ai_platform.sql    # 平台库 13 张 ai_* 同构表 + 平台世界基线（base + base-scope: platform）
│   ├── V20261002_02_ai__biz_analyst.sql    # biz_analyst(8007)+biz_query(8103) 种子（master + per-tenant）
│   ├── V20261002_03_ai__biz_analyst_platform.sql  # 8007/8103 平台库份（base + base-scope: platform）
│   ├── V20261003_01_ai__chat_trace_permission.sql # 权限点 8024 ai:chat:trace（base）
│   ├── V20261003_02_ai__biz_analyst_prompt_stop.sql # biz_analyst 空结果即停提示词（master + per-tenant）
│   ├── V20261004_01_ai__agent_seed_retire.sql      # 业务智能体平台种子退役→Provider 声明登记（master）
│   └── V20261004_02_ai__agent_seed_retire_platform.sql # 同上平台库份（base + base-scope: platform）
└── docker/        # nacos/config/mse-ai.yaml 配置留档 + milvus/compose.yaml 部署物
```

资源分配：端口 8094 / 前端 3800 / 权限号段 8xxx / micro_app id=7 / 菜单 460-469（登记见 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md`）。

## 快速开始

1. **基座入仓**：根目录 `mvn install -DskipTests`（本工程通过 BOM 消费基座发布件）；
2. **建库**：免手工执行——单库演示直接启动即可：业务表脚本（mse-target: master，业务表 + 示例智能体 `general`）随服务启动自动应用（`mse.tenant.schema.auto-migrate=true`，失败阻断启动）；基座接入脚本（mse-target: base，子应用/菜单/权限点）由 starter-bridge 就绪即推 mse-system 按账本应用；分库部署时 base 推至基座库、init 应用至业务主库与租户独立库（克隆+迁移自动就绪）；
3. **Nacos 配置**：`CONFIG_DIR=mse-modules/mse-ai/docker/nacos/config sh mse-framework/docker/nacos/init-configs.sh` 导入 `mse-ai.yaml`（端口、`mse.ai.*` 等）；租户隔离零配置——全部 ai_* 表带 tenant_id，业务端裸调、随租户世界路由（一切皆租户：独立库租户落专属库、共享租户落绑定共享库、平台世界 0 落平台库/降级主库，判定在基座租户插件），系统工具（builtin/local/rpc 自动登记）保持平台登记源平台库 0 行（runIgnore 不路由、落 primary=平台库）+ 租户 http/dify 自有行；
4. **启动**：`java -jar ai-boot/target/ai-boot-1.0.0-SNAPSHOT.jar`（网关需同步更新 `mse-gateway.yaml` 路由留档并重导）；
5. **配模型**：「AI 服务 → 模型与智能体」新增模型：先选**模型类型**（对话模型 / 向量模型 / 重排模型，一行只表达一种能力），再选供应商自动带出内置端点与接口类型（如 DeepSeek 选 `https://api.deepseek.com` 走 OpenAI 兼容、选 `https://api.deepseek.com/anthropic` 走 Claude 接口），端点可下拉选内置 URL 或手改，点「测试」验证连通（向量模型测试会回显向量维度）；

   > DeepSeek 无向量端点：需要 RAG 时**另建一行向量模型**（类型选「向量模型」）配智谱 `embedding-3` 或 Ollama `nomic-embed-text`，并在模型中心点「设默认」（也可在每个知识库单独绑定）；`mse.ai.embedding-model-id` 为旧版全局配置，兼容保留、可选。

   > 讯飞星火选「讯飞星火」预设自动带出 OpenAI 兼容端点 `https://spark-api-open.xf-yun.com/v1`，API Key 填星火控制台的 **APIPassword**（不是 APPID/APISecret），模型名如 `4.0Ultra` / `x2` / `x1` / `max-32k` / `pro-128k` / `lite`；星火响应为非 OpenAI 标准格式（非流式缺 `id/object/created/model/finish_reason`、流式帧缺 `model`），`AiModelFactory` 已内置 `OpenAiCompatInterceptor` 垫片按需补齐缺失字段，标准 OpenAI 端点零影响；星火向量服务独立鉴权且非 OpenAI 兼容，需要 RAG 时同样另建向量模型行配智谱 `embedding-3` 或 Ollama `nomic-embed-text`。
6. **对话**：租户端「AI 服务 → 对话工作台」选「通用助手」开聊。

知识库 RAG 需先 `docker compose -f mse-modules/mse-ai/docker/milvus/compose.yaml up -d`，再置 `mse.ai.milvus-enabled=true`（向量模型在模型中心维护并在知识库绑定，无需再配 `embedding-model-id`）。

## 模型类型与知识库向量模型绑定

- **类型区分**：`ai_model.model_type` 三选一——`chat`（对话模型，智能体/工作流绑定）、`embedding`（向量模型，知识库绑定）与 `rerank`（重排模型，知识库精排绑定），新增/编辑时先选类型、表单按类型显隐；所有选择点按类型过滤（智能体/工作流下拉仅对话模型，知识库表单仅向量/重排模型）；
- **默认向量模型**：模型中心对向量模型行点「设默认」（全局唯一，`is_default=1`）；新建知识库默认选中默认行，可改绑任意向量模型；
- **按模型分集合**：Milvus 集合按向量模型路由（`mse_ai_kb_<modelId>`；旧配置 `mse.ai.embedding-model-id` 指向的模型沿用 `mse_ai_kb_v2`，存量向量零迁移），维度在建集合时按模型实测，不同维度向量模型互不影响；
- **向量模型解析优先级**：知识库绑定 `embedding_model_id` > 旧版配置 `mse.ai.embedding-model-id` > 平台默认向量模型行；
- **改绑后重建**：知识库改绑向量模型后，点「重嵌入」（轻量：不重解析文档，跨集合清场后按现有切片全量重嵌入）；修改切片配置仍走「重建索引」（重新解析）。

## 模型租户自管（平台能力下放）

模型配置按会话世界单边读写：租户会话 = 本租户自有行，平台会话 = 平台世界行（tenant_id=0）；**平台不再维护全局模型**。

- **存量播种**：历史平台公共模型行（含端点/AES 密文）由 `V20261001_03_ai__tenant_seed.sql` 每租户播种为自有副本（副本 id = 源 id + 租户号低 8 位 ×1000），`ai_agent.model_id`、`ai_model.failover_model_id`、`ai_knowledge_base.embedding_model_id/rerank_model_id` 引用同批改指副本；
- **旧机制退役**：「圈定」（ai_model_tenant）与「租户覆盖配置」（ai_model_tenant_config，菜单 469「模型配置」）已随模型租户化退役——租户在「AI 服务 → 模型管理」（菜单 466）直接增删改自己的模型行（典型场景：租户配自己的 DeepSeek/智谱 Key 计费）；
- **运行时**：`AiModelFactory.getChatModel(modelId)` / `getEmbeddingModel(modelId)` 按世界内绑定关系（agent.model_id / KB 绑定）解析模型行，缓存 key = modelId；配置变更即刻失效重建，无需重启；
- **默认向量模型**：`is_default` 语义变为世界内唯一默认；知识库显式绑定仍优先，未绑定回退本世界默认行。

## 模型韧性：瞬态错误重试与备用模型

上游模型平台瞬时抖动（典型如网关 `503 overloaded_error "no healthy upstream"`、429 限流、网络中断）不再直接把对话回合打失败：

- **自动重试**：对话模型调用遇瞬态错误自动退避重试，次数与间隔由 `mse.ai.chat-retry-times`（默认 2，0=不重试）与 `mse.ai.chat-retry-backoff-millis`（默认 500）控制；瞬态判定按官方客户端异常（openai-java / anthropic-java 的 Retryable/Io 异常直接判定，Service 异常按 HTTP 状态 ≥500 或 429 判定）——4xx 参数/鉴权类错误快速失败，不重试；
- **备用模型**：模型中心编辑对话模型时可选「备用模型」（`ai_model.failover_model_id`，必须是对话模型且不能是自身）；主模型重试耗尽仍失败时整体切换备用模型再来一轮，对话工作台 / RPC 无状态通道 / 工作流 LLM 节点全链路生效；备用按行配置直接构建、不嵌套备用（failover 深度 1，天然防环）；
- **流式安全**：SSE 流式仅在「尚未向下游发出任何 token」时才允许重试/切换（已吐内容后出错无法安全重放，直接报错交由会话标记失败）；重试耗尽上抛的是上游原始错误，会话里能看到真实报文而非包装异常；
- **缓存联动**：备用行配置变更时，把该行配为备用模型的引用行缓存一并失效（`AiModelFactory.invalidate` 联动）；备用行被删时引用行运行期降级为主模型单跑（warn 日志），不阻塞主链路。

## 其他服务怎么用（三种通道）

### ① RPC 契约（服务间同步调用）

```xml
<dependency>
    <groupId>vip.mseapp</groupId>
    <artifactId>ai-api</artifactId>
</dependency>
```

```java
@RpcReference
private RemoteAiChatService aiChatService;

AiChatRequest request = new AiChatRequest();
request.setAgentCode("general");          // 或业务专属智能体
request.setMessage("帮我总结这段文本：" + text);
request.setConversationId(sessionId);     // 可选：续接多轮；为空=无状态单轮
AiChatResult result = aiChatService.chat(request);
```

单体（local 模式）本地直调、微服务（dubbo 模式）远程调用 mse-ai，消费方代码零改动；租户上下文经 RPC 透传，可见范围/用量自动按调用方租户。

另有只读契约 `RemoteAiAgentService.listOptions(keyword)`：返回当前会话世界的已发布智能体选项
（DTO `AiAgentOption`：agentCode/name/description/scope），供业务服务管理页
做「场景 → 智能体」映射的下拉数据源——参考 mse-oa「AI 智能体设置」页（`mse-oa/AI-INTEGRATION.md` 第八节）。

### ② HTTP 网关（前端 / 脚本）

租户登录态走 `POST /api/ai/chat/stream`（SSE）；第三方系统走开放通道：

```
POST {gateway}/openapi/ai/chat
X-App-Id: ai-xxxx
X-Api-Key: mk-xxxx
{"agentCode":"general","message":"..."}
```

### ③ 工具注册（把业务能力变成 Agent 的工具）

业务服务实现 `RemoteAiTool` 并以 **group=服务名** 导出（`@RpcService` 不支持 group，用编程式导出）：

```java
@Component
public class OaLeaveTool implements RemoteAiTool {
    public AiToolDescriptor describe() { /* toolCode=oa:leave:query + 名称/说明/JSON Schema */ }
    public AiToolResult invoke(String argumentsJson) { /* 查询业务数据，返回 JSON 文本 */ }
}

@Bean(initMethod = "export", destroyMethod = "unexport")
@ConditionalOnProperty(name = "mse.rpc.mode", havingValue = "dubbo")
public ServiceConfig<RemoteAiTool> oaAiToolConfig(RemoteAiTool impl, ConfigurableListableBeanFactory bf) {
    ServiceConfig<RemoteAiTool> config = MseDubboSupport.serviceConfig(RemoteAiTool.class, impl, bf);
    config.setGroup("mse-oa");            // spring.application.name
    return config;
}
```

RPC 工具由提供方自动推送登记（见下节「RPC 工具自动登记」），智能体编辑勾选该工具即可让模型调用业务能力（单体聚合形态无需导出——平台直接收集容器内 `RemoteAiTool` Bean；微服务形态 mse-ai 经 Dubbo 泛化调用，无需编译期依赖提供方）。

**RPC 工具自动登记（微服务形态免手工维护）**：业务服务依赖 ai-api 即自带自注册推送装配（`AiToolRegistrationPusher`）——应用就绪后把容器内全部 `RemoteAiTool` 的 `describe()` 描述推送至 mse-ai（幂等 upsert 进 `ai_tool`：新增平台行或按 toolCode 更新 name/description/schema），并按周期重推自愈（`mse.ai.tool-registry.interval-minutes`，默认 5 分钟；mse-ai 未就绪/重启丢失自动补登记）。规则：

- 描述以推送为准；管理端手工改动 name/description/schema 会被下轮推送覆盖，**启停状态（status）与备注保留**不被覆盖；
- toolCode 已被其他提供方（rpc_group 不同）或租户自有登记占用时跳过并告警，防互相覆盖；
- 宿主容器内已有 `RemoteAiToolRegistry` 实现（单体聚合 ai-service / mse-ai 自身）时推送器闲置——本地扫描已覆盖；local 模式且未聚合 mse-ai 时告警一次后闲置（工具无消费通道）；
- **多实例不重复执行**：提供方每个实例都会周期重推（内容相同），接收端按 provider 分组做 `tryEnter` 窗口标记（30 秒），同一提供方一个窗口内的成簇推送只处理一次；处理时比对 name/description/schema，**无变化不落 update**——错峰重推也零写库；Redis 异常降级直收。

**系统工具自动登记（双形态一致，替代历史手动拉取兜底）**：mse-ai 启动时扫描容器内全部 `RemoteAiTool` Bean（内置工具 + 单体聚合形态下各业务积木的实现）幂等 upsert 进 `ai_tool`（source=builtin/local），工具中心可统一启停/测试/审计——单体聚合形态 oa/data 等业务工具开箱即得，无需任何手工登记。描述字段以提供方为准（name/description/schema 由扫描同步），**启停状态与备注保留**；删除后服务重启会自动重新登记。**单体聚合形态下 source=rpc 的登记行（种子/历史推送）同样由本地扫描同步描述字段**——能进扫描即提供方 Bean 已在宿主容器内，本地 Bean 就是描述权威，存量种子行由此自愈拿到补充后的参数描述。**多实例防重（三层）**：① 启动扫描经 `LockOps.tryExecute` 单飞——同时启动的多个实例只有一个执行登记，其余跳过不碰数据库（无 Redis 时降级直执）；② `ai_tool` 唯一键 `uk_tool_code (tool_code, deleted)` 数据库层兜底并发首启插入竞态，落败方转同步既有行；③ 变更比对保证无变化零写（错峰启动的后启实例只做一次只读扫描）。

**租户副本懒补齐（清单与「绑定工具」下拉一致）**：登记只治理平台世界行（tenant_id=0），播种脚本仅覆盖建租户一刻——此后平台侧新登记/内容演进的工具不会再进既有租户世界，租户工具中心会停在旧快照（微服务形态租户绑定的 rpc 工具还会因缺租户登记行而解析失败）。`AiToolService.ensureDeclaredTenantCopies` 在读取入口（工具中心分页 / 绑定下拉 / 对话解析·每世界 60s 节流）把平台登记行补齐到当前世界：缺副本 → 补插（内容随平台行，雪花 id）；系统来源（builtin/local/rpc）副本内容漂移 → 以平台行为权威刷新 name/description/schema/source/rpc_group（**启停/备注保留**，与 save 的 providerOwned 归一同口径）；http/dify 租户自有行不跟随平台行。幂等容错：同码跳过、并发撞 `uk_tool_world_code` 忽略、失败不阻断本次查询（下个入口重试）。登记来源已零 SQL 种子（`V20261012_01/_02` 退役 3 行历史 rpc 引导行，重建后 source 归位正确——单体聚合=local、微服务=rpc；微服务形态 rpc 工具重建前有最长一个推送周期的登记空窗）；工具中心的 MCP 外部工具行取绑定下拉同源 options 清单动态追加（只读、可测试）。

**工具描述模型可见性（必填约束）**：功能说明与每个参数的说明是模型判断「何时调用、怎么传参」的唯一依据——手工维护来源（rpc/http/dify）保存时后端强校验：功能说明必填、参数 Schema 每个属性必须带非空 `description`（前端登记表单与图形化参数编辑器同步拦截）；builtin/local 由提供方代码与启动扫描负责。自动登记（本地扫描/RPC 推送）原样携带提供方 `describe()` 中的参数描述。

**工具测试（双模式入参）**：工具中心「测试」弹窗按参数 Schema 展示全部参数（说明/类型/是否必填），支持两种模式——**表单填写**：逐参数填值（基础类型控件、枚举/布尔下拉、数组/对象 JSON 文本，必填项校验），**JSON 源码**：直接粘贴整包入参；两种模式切换时双向同步值，调用结果就地展示（成功/失败 + 内容）。

**外部工具（HTTP / Dify，手工登记）**：工具中心「登记工具」按来源配置——`HTTP 接口`：静态 URL + 请求方法 + 请求头（可放鉴权）+ 可选 Jinja2 Body 模板，模型入参进 query（GET/DELETE）或 JSON body（POST 类），**URL 不做模板渲染**（杜绝参数注入改写目标地址）；`Dify 应用`：baseUrl + 应用 API Key + 应用类型（workflow/completion/chat），blocking 调用并提取 outputs/answer。连接配置存 `ai_tool.config_json`（密钥明文落库，注意库权限管控）；**租户端可自建外部工具**（登记为租户自有行 `tenant_id>0`，随租户路由——独立库租户存自己库、共享池按行级隔离，仅本租户可见可用，运行时解析按「自有行优先 + 平台公共行」合并；`toolCode` 查重覆盖业务主库与本租户自有行，rpc 来源不对租户开放）；绑定侧：智能体编辑与画布工具节点的下拉选项自带说明与参数 Schema，画布工具节点按 Schema 生成参数表单（值支持上游变量插入），MCP 工具以 `mcp:{server}:{tool}` 列入下拉。

## 业务模块化查询（biz_query 路由门面 + 模块 biz 查询工具）

跨模块业务数据问答的架构：**mse-ai 零业务库连接**——`biz_query` 是平台自持的路由门面（`ai-service` `tool/BizQueryTool`），按模块注册表把查询**转发到归属业务模块自提供的查询工具**，在模块自身进程内用其自有租户路由连库执行（数据不出归属服务；微服务经 Dubbo 泛化调用 group=归属服务名，租户上下文随 `MseDubboContextFilter` 透传；单体容器内本地 Bean 直调）。

- **模块自声明**：业务积木实现 `AiBizModuleProvider`（ai-api 契约，`@Component` 返回 `AiBizModuleDescriptor`：moduleCode/name/description/serviceName/keywords/tablePrefixes/toolCode），登记进 `ai_biz_module`（平台全局目录，runIgnore 读平台行，无租户副本）——单体 `LocalBizModuleAutoRegistrar` 启动扫描 / 微服务 ai-api 推送器周期重推（`RemoteAiBizModuleRegistry` 契约，范式与智能体/工具注册完全对称），零 SQL 种子；管理端 `/ai/biz-module/list` 只读 + 启停（挂 `ai:tool:list`/`ai:tool:manage`）。
- **模块查询工具**：业务积木继承 ai-api `AbstractBizQueryTool`（子类只声明 toolCode/moduleCode/moduleName/toolName/toolDescription/tablePrefixes），基类实现 `meta_search`（实时 JDBC 元数据检索本模块表目录：表名/注释关键字，模块前缀白名单 ∩ `mse.ai.biz-query.deny-*` 硬黑名单，空结果=最终结论自带终止 note）/ `table`（列清单）/ `execute`（guardBiz 前缀防护 + 逐表探测动态注入 tenant_id/deleted=0 + 限行限流）。连接经 `TenantRawConnectionRouter.open(子类类名)` 按 @TenantServiceModule 解析本模块世界——独立库租户→租户专属库、共享租户→本模块共享库，**每个业务库只有归属服务自己碰**，天然无跨服务可达性问题。
- **路由工作流**：通用入口「业务分析师」（biz_analyst，mse-ai `BuiltinBizAgentProvider` 内置声明，工具=biz_query）——① `action=list_modules` 取模块目录（编码/名称/关键词）② 按问题判定 module ③ `meta_search`（keyword 数组一次传全）④ `table` 查列清单 ⑤ `execute`；module 未注册/停用被拒并列出可用模块。
- **模块分析师智能体**：各模块可另声明领域分析师（如 mse-oa「协同服务分析师」`oa_collab_analyst`，直绑本模块查询工具 oa_biz_query），提示词钉死本域，用户在对话工作台直选即走本域。
- **既有接入**：mse-oa（oa / oa_biz_query + 协同服务分析师）、mse-mes（mes / mes_biz_query）、mse-mp（mp / mp_biz_query）；新模块接入三件套 = `XxBizQueryTool extends AbstractBizQueryTool` + `XxBizModuleProvider` +（可选）领域分析师智能体，boot 侧把工具收敛进 `DispatchingAiTool` 导出（同 group 只能导出一个 RemoteAiTool）。
- **历史迁移**：biz_query 原为 mse-data 的业务库直查工具（依赖数据地图手工采集目录，租户世界未采集即检索为空——这正是本次重构的动因）；`V20261011_03/04` 双通道把 ai_tool 8103 行归 mse-ai 自持（source=builtin），biz_analyst 通用入口迁至 mse-ai、data_analyst 更名「数据中台智能分析师」，存量租户副本行由迁移脚本刷新。

## 智能体（Agent）详解

**智能体 = 一个可发布的 AI 应用**，以全局唯一 `agent_code` 编码被引用。五要素配置：

- **绑定模型**：仅对话模型（模型管理按类型过滤；不绑定 = spring.ai.* 默认模型或本世界首个可用模型），配温度控制输出随机性；
- **系统提示词**：设定角色/规则/输出格式，支持 `{{var}}` 占位（会话变量注入）；
- **头像**：`ai_agent.avatar` 单字段兼容三种值——内置 emoji 图标（编辑弹窗「选图标」网格精选集）/ mse-fs 公开素材地址（`/fs/asset/...`）/ 本地兜底地址（`/ai/agent/asset/...`）；上传走 `POST /ai/agent/avatar`（≤2MB png/jpg/jpeg/gif/webp，`AgentAvatarStorage` 双实现：mse-fs 公开素材通道优先、本地磁盘兜底，`mse.ai.avatar.storage-mode/storage-path` 可覆盖），本地兜底素材经 `GET /ai/agent/asset/{name}` 免登录输出（微服务形态需网关 ignore-urls）；前端按 http(s)/站内路径前缀识别为图片、否则按 emoji 文本渲染（智能体列表与对话工作台下拉均带头像展示）；IM 机器人管理页带出该头像作为机器人 OpenIM 头像（见 mse-im README）；
- **绑定工具**：从工具中心多选（内置/本地/RPC/HTTP/Dify/MCP 均入下拉，支持手输自定义编码如 `mcp:maps:geo`），模型在对话中按需调用（function calling），每次调用落审计；**模型侧工具名统一用 `toolCode`**（OpenAI function 名仅允许字母数字下划线连字符，中文名会被严格端点拒绝/丢弃，显示名拼入 description）；**工具执行可视化**：SSE 新增 `tool` 事件（start/result 逐步携带 seq/入参/成败/耗时/失败原因），前端消息内实时显示调用状态；**未调用告警**：完成后绑定了工具却零执行时推 `phase=unused` 事件并打 warn 日志，`resolved` 区分「工具未解析成功（登记/部署问题）」与「模型未调用（端点可能不支持 function calling，需换绑模型）」；**数据结果卡片**：工具结果含 `columns+rows`（如 data_query execute）即提取数据集（行数截 100、单元格截 200 字符），对话页渲染「表格 / 图表」双视图卡片（echarts 按需引入，启发式默认图型：时间特征维度→折线否则柱状，可切柱/条/折/饼；单行结果仅表格），随 `ext` 落库刷新可回放；画布编排经「工具」节点引用同一工具中心；
- **工具调用轨迹（调试可观测，按权限）**：对话工作台每条 assistant 消息下内联「🔧 工具调用（N 次）」折叠面板——逐步展示每次调用的入参 JSON、**返回内容 JSON**（均截 2000 字符，与 `ai_tool_log` 审计同口径）、成败/耗时、失败原因与超限拦截说明（`phase=limit` 事件由 `FriendlyToolCallingManager` 在拦截点经 `ToolTracePropagation` 跨线程回传），步骤带所属模型轮次号（多轮自动按「第 N 轮」分组）；轨迹随消息 `ext.toolCalls` 落库（上限 60 步），刷新/切回会话可回放。**权限点 `ai:chat:trace`（8024，挂菜单 461）**：SSE tool 轨迹事件与历史消息的 `ext.toolCalls` 仅对持权者下发（无权限用户不受影响，数据卡片/引用面板照常）。**工作台「📥 模型输入（N 轮）」面板（同 `ai:chat:trace` 门控）**：持权者对话时后端开启 verbose 轨迹（`AiChatService.enableVerboseTrace`，RPC/开放通道不启用），每轮模型实际输入消息快照（系统提示词 + RAG 注入 + 记忆历史 + 工具调用/回传）经 `phase=turn/turn-end` 事件实时下发、随 `ext.turns` 落库（上限 12 轮）刷新可回放，与工具轨迹面板并列逐轮展开；无权限者 turn 事件不下发、ext.turns 随 toolCalls 一并剔除（`stripTrace`）。**智能体管理「调试」抽屉 v2——全过程时间线（简单配置智能体，权限 `ai:agent:save`）**：输入问题+变量一次性试跑（不建会话、不带多轮记忆，按当前已保存配置，运行中可停止），按模型轮次还原完整过程——每轮「模型输入」（该轮实际发送的完整 prompt 消息快照：系统提示词 + RAG 注入 + 工具调用/回传，由 `FriendlyToolCallingManager` 在每轮 `executeToolCalls` 边界经 recorder 下发 `phase=turn/turn-end` 事件，仅调试通道 verbose 开启）、每轮输出文本、每轮工具调用（入参/返回/耗时）、引用来源、数据集卡片与最终回答；汇总条展示状态/总耗时/轮次数/工具次数/token（`usage` 按轮求和修复原「最后一轮覆盖」少报）；另含抽屉内存级运行历史（最近 ≤10 次点击回看）、原始事件逐条格式化查看（独立面板 `RawEventsPanel`：时间 + 类型徽标 + 关键字段中文摘要，点击展开该事件完整 JSON，头部事件类型统计与一键复制，连续文本增量合并为累积条目；走 `POST /ai/agent/{id}/debug-run`，SSE 另有 `prompt` 事件下发初始提示词；画布编排智能体的试运行仍在画布编辑器内）；调试抽屉全屏（内容列宽 1280 居中），轮次卡展示每轮 token 估算（turn 事件附截至该轮累计用量，前端相邻边界差分），「🛠 工具运行记录」面板（`ToolRunsPanel`）跨轮次按时间序整合全部工具执行的完整入参/返回/数据集卡片，空数据返回高亮提示、同参数重复调用计数；工具中心「调用审计」页支持按工具/智能体/会话号过滤，与 `ai_tool_log` 全量审计互补定位问题现场；- **绑定知识库**：多选启用 RAG 检索增强，回答附引用来源；
- **编排模式**：`simple` 简单配置（提示词 + 工具 + 知识库直接驱动对话）与 `flow` 画布编排（工作流 DSL 驱动，见下节）；
- **直出图表（`chart_direct`，仅 simple 生效）**：开启后对话运行时（`AgentRuntime.resolveSystemPrompt`）在系统提示词末尾追加「输出纪律」——查询类工具成功后只回一句话极简结论（≤25 字），严禁思路/表格/口径说明，查询结果由数据结果卡片直出；并要求 SQL 输出列一律中文别名、枚举维度 CASE WHEN 映射中文标签（图表坐标轴/图例/表头全中文）。声明智能体经 `AiAgentDescriptor.chartDirect` 声明默认值（平台行随提供方刷新，租户副本可在智能体管理开关）；默认开启的分析师：biz_analyst 业务分析师（存量租户副本由 `V20261020_01` 对齐，平台库份 `V20261020_02`）与 mse-oa oa_collab_analyst 协同服务分析师（存量租户副本由 `V20261021_01` 对齐）。
- **工具调用跑飞保护**：`AiModelFactory` 为每个对话模型注入定制的 `ToolCallingManager`，单回合工具调用上限 `mse.ai.tool-max-calls-per-tool`（单工具，默认 10）与 `mse.ai.tool-max-total-calls`（合计，默认 30）——模型参数抄错（如长整型编号）时不再反复道歉重试数十轮才被 Spring AI 默认上限（40/150）拦住，超限以「已达上限」的工具响应回传让模型自行收尾作答（`RETURN_ERROR_RESPONSE`，而非抛异常中断整个流）；Spring AI 的超限回传文案是硬编码英文（模型会原样复述给用户），`FriendlyToolCallingManager` 装饰器在结果回传前改写为中文收尾指令（停止调用、基于已获取信息作答）——模型照做即自然收尾，即便复述用户看到的也是可读中文；**空结果立即终止**：工具「成功但无数据」（结果 JSON 的数据数组字段 rows/tables/records/data/list/results/items 全空，如 `{"total":0,"tables":[],"tip":…}`）对模型没有终止语义，且 tip（如"用 table 表名或 metaTableId 查看列清单"）反而引导模型换 keyword/方式继续调——装饰器对空结果**首次即整条替换为硬终止指令**（终止指令置顶：勿再调用任何工具包括换 keyword/表名/条件；原始返回内嵌保留供作答引用 tip），不给"换方式再试一次"的机会，经 recorder 推 `phase=nodata` 轨迹事件（调试台显示提示，工作台不展示）；工具异常重试原则不变（失败仍回传错误文本由模型有限重试，上限兜底）；**双层机械停止**（不依赖模型服从指令）：① 回调层（`AiToolService.executeAndLog`）同工具累计 2 次空结果即机械关闭，本对话内后续调用短路直返终止指令、不再执行；② 装饰器发现关闭工具再次被请求时抛 `ToolCallLimitExceededException`——Spring AI 2.0 的 `ToolCallingAdvisor` 工具循环仅有三个出口（模型不再要工具 / 全部 returnDirect / 捕获该异常），此为唯一机械终止通道，advisor 捕获后以 partialResult 末条工具响应（即终止指令文本）作为最终回答收尾，循环立即结束；**spring.ai.\* 兜底模型同口径**：`AiServiceAutoConfiguration` 排在 Spring AI `ToolCallingAutoConfiguration` 之前注册同一构建（`FriendlyToolCallingManager.fromProperties`，两处共用不漂移）——智能体未绑定模型走 starter 自动装配 ChatModel 的兜底路径不再落回库默认 40/150 + 英文文案；编排工作流的 LLM 类节点同样生效（共用模型工厂）。

**归属与发布**：智能体全面租户自管——每会话世界一份（租户自有行 / 平台世界行）。平台种子只剩 general（mse-ai 自家 builtin，`V20261001_02`/`V20261002_01` 双通道播种）；oa_* 四个与 data_analyst/biz_analyst 的平台种子已退役（`V20261004_01/02`，2026-09-30）——改由 mse-oa `OaAiAgentProvider`、mse-data `DataAiAgentProvider` 与 mse-ai `BuiltinBizAgentProvider`（业务分析师通用入口）声明登记 + 租户懒补齐承接（存量租户副本由 `V20261011_03` 刷新对齐，编码不变故 OA/MES 场景映射不受影响）；仅「已发布」状态的智能体进入对话/开放通道，可随时下线。

**来源与声明式注册**：`ai_agent.source` 标记行的来源——`builtin` 平台种子 / `local` 模块声明登记（单体） / `rpc` 模块注册登记（微服务推送，`rpc_group`=提供方服务名） / `custom` 页面自建。业务积木实现 `AiAgentProvider`（ai-api 契约，`@Component` 返回 `AiAgentDescriptor` 清单：编码/名称/头像/描述/提示词/工具 CSV/直出图表默认值）即自动注册：单体聚合形态 `LocalAgentAutoRegistrar` 启动扫描容器内 Provider 幂等 upsert 平台行（Redis 单飞锁、dirty-check 零写），微服务形态 ai-api 推送器周期重推（`RemoteAiAgentRegistry` 契约 + 30 秒同提供方收敛窗口，间隔 `mse.ai.agent-registry.interval-minutes` 默认 5 分钟）——与工具自注册（`RemoteAiTool`）完全对称。平台行的编码/名称/描述/提示词/工具/直出图表五要素以提供方声明为权威（登记幂等刷新，管理端编辑弹窗对应字段只读、防 API 直调覆盖），头像仅登记首建填充（此后管理侧权威），模型/温度/开场白/启停/备注管理侧保留；各租户首次经可见性/对话入口（`listVisible`/`getPublishedVisible`）懒补齐自有副本（区别于种子脚本的 builtin 播种通道，既有租户零 SQL 即见即用），副本完全可编辑（改提示词/绑模型/知识库/直出图表开关）。先例：mse-mes `MesAiAgentProvider`（8 个 mes_* 智能体）、mse-oa `OaAiAgentProvider`（5 个 oa_* 智能体，含协同服务分析师）、mse-data `DataAiAgentProvider`（data_analyst 数据中台智能分析师）、mse-ai `BuiltinBizAgentProvider`（biz_analyst 业务分析师通用入口）——业务提示词权威均自种子 SQL 迁回提供方，业务种子已随 V20261004 全面退役（平台种子只剩 general）；同码已被其他提供方 rpc 登记时跳过防互相覆盖，租户自有行登记不触碰。

**「复制为自有」退役**：租户「智能体管理」列表即本租户全部自有智能体（含种子副本），全部可直接编辑/发布/删除——原「平台公共行只读 + 复制为自有 fork」模式随租户化退役（`/ai/agent/platform/*` 平台端点组与 `ai:agent:platform` 权限点同步退役）。

**消费入口（发布后）**：① 租户端对话工作台会话（SSE 流式）；② 业务服务 `@RpcReference RemoteAiChatService`（单体本地直调 / 微服务 Dubbo 远调，消费方零改动）；③ 第三方系统开放 API（`/openapi/ai/chat`，appId + Key + 限流审计）；④ 调试试运行（智能体管理「调试」抽屉 / 画布编辑器试运行，不建会话不落消息）。

## 智能体画布编排（mse-flow/v1）

`orchestration_type=flow` 的智能体不再走"提示词+工具"简单模式，而是执行画布编排图（`ai_agent.graph_json`）：

- **DSL**：`{"nodes":[{"id","type","title","data","position"}],"edges":[{"source","target","sourceHandle"}],"globals":[{"name","value","type","remark"}]}`；`sourceHandle` 为分支出口（`case0..N`/`default`）；`globals` 为画布全局参数（`type` 支持 string/number/boolean/array/object，缺省 string 兼容旧图；运行时按类型转换默认值并以 `env.<name>` 预置，变量赋值节点可在执行期按操作改写）；迭代节点体为内嵌子图 `data.graph`。
- **引擎语义**：从开始节点沿激活边拓扑执行；条件/分类节点仅激活命中分支；**多活跃路径并行执行**（同一轮可执行节点经节点池并发跑，OR-join 先到先跑、至多执行一次；failfast 失败批内取消同级后冒泡；`mse.ai.flow.parallel` 可回退串行，默认并行；上下文集合并发安全）；节点级 `errorStrategy`（failfast/continue）；全局超时 `mse.ai.flow.timeout-millis`（默认 120s）；LLM 节点逐节点记用量流水
- **节点清单**：start / llm（节点级模型/温度覆盖+多轮记忆注入） / knowledge（走 `KbRetrievalService` 统一检索管线） / tool（工具中心单工具直调，参数=变量模板） / dataset（**数据查询**：默认复用 mse-data 的 `data_query` 工具通道——jsqlparser 只读校验+租户谓词注入+行数上限，输出 columns/rows/markdown/rowCount，markdown 供 LLM 直接阅读；**数据源为下拉选择**——经 `data_query` 的 `datasource_list` 动作取「中台库 + 本租户已启用数据源」（`GET /ai/agent/datasource-options`），运行期双重校验归属（租户行级隔离 + `ApiQueryExecutor` 显式比对），不能跨租户取数） / condition（contains/eq/gt/regex 等 13 种运算符） / classifier（LLM 单次归类路由） / extractor（LLM 按 JSON Schema 抽参） / code（**JavaScript GraalJS 沙箱**：禁宿主访问/IO，`function main(args){return {...}}`，限时 `mse.ai.flow.code-timeout-seconds`） / http（JDK HttpClient，默认禁内网地址 SSRF 防护+`mse.ai.flow.http.allowed-hosts` 白名单） / template（Jinja2/Jinjava，把多个上游变量拼接/排版为一段文本） / **assign（变量赋值**：执行期把「全局参数 → 变量模板」取值按操作写回 `env.*`，后续节点引用即得新值。操作（`assignments[].op`，缺省 `set`）：`set` 覆盖（按目标参数声明类型转换） / `reset` 重置为全局参数默认值 / `append` 追加（文本尾拼或集合尾插，操作数为数组则整段拼接） / `add` 累加、`subtract` 累减（数值，整型保精度） / `merge` 合并（集合拼接、对象浅合并）；迭代子图内赋值经上下文父链透传回主流程，累加计数/追加收集跨迭代累计生效） / aggregator（多分支取首非空） / iteration（对数组逐项顺序执行子图，`maxItems` 防爆炸） / answer（流式回复；对已流式 LLM 的纯引用自动去重） / end（结构化输出，RPC/开放 API 同步通道取此值）
- **变量引用**：`{{nodeId.var}}` 引用任意上游祖先节点输出（引擎按 ctx 全量取值，不限于直连上游）；开始节点自定义入参按真实节点 id 预置（`{{start1.入参名}}`，旧图 `{{start.入参名}}` 别名兼容）；系统变量 `sys.query/conversation/...`；全局参数 `env.<name>`；HTTP 响应为 JSON 时支持深层取值（`{{http1.json.data.name}}`，逐段下钻 Map 键/List 下标）
- **对话集成**：`AiChatService.prepare` 按 orchestration_type 分流——工作流引擎显式携带租户上下文（不依赖 ThreadLocal）；SSE 契约新增 `node` 事件（nodeId/status/elapsedMs/output 摘要），对话页消息内嵌「执行过程」时间线；有会话时运行成功后自动落「用户消息+最终回答」进会话记忆（多轮连续）
- **草稿/发布与运行历史**：画布「保存草稿」写 `draft_graph_json`（试运行/DSL 导出按草稿——发布前验证，无草稿的存量智能体回退运行版），列表/画布「发布」校验后拷贝为运行版 `graph_json` 并置已发布，**编辑不影响线上**；每次编排执行收口落运行历史 `ai_workflow_run`（通道 gateway 对话/debug 试运行/rpc/openapi，结果 success/failed/cancelled，轨迹快照 `traces_json`），智能体列表「运行」弹窗分页查看 + 轨迹时间线回放（`GET /ai/agent/run/page|/{id}` 权限 `ai:agent:list`）。
- **编辑器**：`/agent/flow/:id`——左侧节点面板 / 中部 vue-flow 画布（Delete/Backspace 删除选中，Ctrl+Z/Ctrl+Shift+Z 撤回重做，左下 ？ 有使用说明；**节点卡片直接显示输出变量清单，点击即复制 `{{ref}}` 供下游粘贴**） / 右侧属性面板（上游变量选择器**按节点分组**展示——系统变量/env 全局参数/全祖先节点输出（含开始入参与提取字段），**在光标处插入** `{{ref}}`，条件比较值、迭代输出变量（子图内节点产出）等全部输入位可插变量）；顶栏「全局参数」弹窗维护画布级参数（校验命名与唯一性；可选类型 文本/数值/布尔/集合/对象，默认值编辑器随类型联动并按类型校验，集合/对象填 JSON）；变量赋值节点每项可选操作（覆盖/重置/追加/累加/累减/合并，操作下拉按目标参数类型过滤，重置无需填值）；迭代节点进入子画布（面包屑返回）；底部试运行抽屉（入参表单→节点时间线→回答流式区，走 `POST /ai/agent/{id}/debug-run` 权限 `ai:agent:save`，不建会话不落消息）；DSL 导入（自动识别/Dify）/导出（mse-flow/v1 YAML）
- **Dify DSL 导入**：尽力映射 start/llm/knowledge-retrieval/if-else/question-classifier/parameter-extractor/code/http-request/template-transform/variable-aggregator/iteration/answer/end/tool 与边（`{{#id.var#}}`→`{{id.var}}`）；模型名/Dify 知识库与工具提供方/Python 代码等不可映射项生成"待人工配置"警告清单；条件分支运算符直映（=/≠/before/after/exists 等；in/not in 按包含处理并告警）
- **数据分析模板**：新建 flow 智能体时一键套用「数据分析助手」——参数提取（时间范围/指标）→ 数据中台取数 → 条件分支（有数据?）→ LLM 结合系统数据做趋势/异常/归因分析 → 回复，空数据分支兜底。取数走 `data_query`（mse-data 微服务自动推送登记 RPC 工具、单体聚合自动本地登记，均开箱可用）
- **部署注意**：代码节点 JS 引擎为运行期依赖——ai-boot 已内置 `org.graalvm.polyglot:js-community`（type=pom 聚合）；单体聚合形态如需代码节点，在聚合工程补同款依赖

## 技术要点

- **Spring AI 2.0**：ChatClient/Advisor 2.0 API；OpenAI 模块换官方 `openai-java` 客户端，`OpenAiChatOptions.builder().baseUrl().apiKey()` 直配动态多模型（SDK 在 baseUrl 后追加 `/chat/completions`，端点 URL 自带版本前缀）；anthropic-messages 走官方 `anthropic-java` 客户端（`AnthropicChatOptions` 同风格直配，追加 `/v1/messages`）；ChatMemory 需显式 `conversationId`（会话表 id）；知识库 RAG 自研 `KbRagAdvisor`（BaseAdvisor）替代 `QuestionAnswerAdvisor`——后者仅单路向量召回且无法回传引用来源
- **异步上下文**：SSE 订阅回调运行在 Reactor 线程——基座 starter-web 开启 Reactor 自动上下文传播（`Hooks.enableAutomaticContextPropagation` + `TenantContext` ThreadLocalAccessor），订阅线程的租户在回调线程自动恢复；编排三池（flow-run/flow-node/flow-code）经 `ContextExecutorService.wrap` 装饰透传；知识库解析走独立线程池（`aiKbParseExecutor`），租户上下文显式传参恢复；落库统一以行上/上下文显式 tenantId `runAs` 锚定世界
- **租户隔离（一切皆租户）**：全部 ai_* 表随世界路由（判定在基座拦截器）——独立库租户落专属库免注入、共享租户落绑定共享库 + tenant_id 行级注入、平台世界（租户 0）落平台库免注入（未接入 platform 数据源的环境降级主库 + tenant_id=0 注入）；智能体/模型/MCP/工具/用量/会话/消息/审计/编排历史全面真租户化，`agentCode`/`toolCode` 世界内唯一（唯一键 `uk(tenant_id, code, deleted)`）；系统工具自动登记（builtin/local/rpc 推送）治理平台登记源平台库 0 行（runIgnore 不路由、落 primary=平台库，兼作每租户播种的 0 行来源），平台库 `V20261002_01` 脚本建同构表 + 静态基线；模型主键读取世界未命中时回退平台世界行（旧版全局配置 `mse.ai.embedding-model-id` 兼容）
- **开放 API 凭证世界化**：appId 内嵌世界段 `ai-{tenantId}-{hex}`（同 mse-im 回调 / mse-fs 素材 URL 范式）——verify 解析世界段 `runAs` 切库校验，密钥行随租户世界驻留；旧格式 appId（无世界段）主库兜底（deprecated，独立库租户旧 Key 请删除重建换发）
- **向量隔离**：Milvus 按向量模型分集合 `mse_ai_kb_<modelId>`（旧全局集合 `mse_ai_kb_v2` 由 `mse.ai.embedding-model-id` 指向的模型沿用），schema 为 id/content/dense/sparse/tenantId/kbId/docId，标量过滤 `tenantId/kbId` 收口；content 列启用中文 analyzer，BM25 Function 服务端自动生成稀疏向量；切片 id = `ai_kb_chunk.id` 双向追溯；dense 维度建集合时按模型实测
- 单体聚合：`mse-monolith` pom 加 `ai-service` 依赖即可（SPI 自装配，租户隔离零配置）

## 已知限制

- OpenAI **Responses API 暂不支持**：Spring AI 2.0.1 上游未实现（issue #2962，ChatModel 仅对接 Chat Completions），接口类型枚举预留 `protocol` 字段待上游支持后扩展
- anthropic-messages 协议无向量端点：向量模型统一走 OpenAI 兼容协议（模型中心建向量模型行时接口类型强制 openai-chat），Claude 对话模型与向量模型分行建档互不干扰
- 向量/重排模型与对话模型统一参与租户圈定与租户覆盖（采样参数仅对话模型有效）；知识库各自绑定向量模型（空 = 旧配置/平台默认解析），改绑后需「重嵌入」
- **Milvus 2.5+ 为全文检索门槛**：2.5 以下服务端自动降级纯向量召回（`/ai/kb/vector-store-status` 会提示升级）；混合检索 collection 为 `mse_ai_kb_v2`，从旧版 `mse_ai_kb` 升级后知识库需重建索引入新集合
- 知识库解析为单机异步线程池：重启后"待处理/处理中"文档不会自动恢复，需手动重解析；多副本部署共享 Milvus 时解析幂等（先清旧再入新），但归档目录需共享存储或按副本落盘
- 知识库归档存储统一走 mse-fs（可降级）：`mse.ai.kb.storage-mode=auto`（默认）时——部署 mse-fs（微服务）或单体聚合 fs-service，归档统一存 mse-fs（`storage_path=fs:{fileId}`，表结构零变更，原文可经 `GET /ai/kb/{kbId}/documents/{docId}/file` 回下载）；未引入/未部署 mse-fs 自动回落本地目录 `mse.ai.kb-storage-path`，历史本地归档切到 mse-fs 后仍可读（按前缀分流）
- RPC 通道（`RemoteAiChatService`）的 `sources` 仅非工作流编排智能体返回（编排链路的来源在知识节点输出中，未并入顶层结果）；会话续接（conversationId 非空）多轮场景同样返回
- source=rpc 的登记工具仅在 `mse.rpc.mode=dubbo` 下经 Dubbo 泛化调用；local（单体）模式自动回落容器内同编码 Bean（提供方积木未聚合则该工具不可用）；source=local/builtin 的系统工具登记行停用后解析直接跳过（未登记的本地 Bean 仍兜底可调）
- 外部工具（http/dify）连接配置与密钥明文存 `ai_tool.config_json`；Dify blocking 调用受平台侧/服务端双重超时约束，长工作流建议拆短或改异步回调方案
- MCP Server 端点为全量开关，暂无按智能体的细粒度暴露控制
- `mse.rpc.mode` 变更后需同步重启（工具解析按模式分流）
- Spring AI 2.0.1 构建基线为 Boot 4.1.x，本仓库锁 Boot 4.0.8（BOM 仲裁压回）；如遇兼容问题可整体升级 Boot 4.1
- 编排引擎「流程市场」（智能体/工作流模板共享）属后续演进（多活跃分支并行执行、草稿/发布双版本、运行历史落库均已实施）
- 代码节点仅支持 JavaScript（GraalJS 社区版）；沙箱限时不替换 GraalJS 引擎级资源限额，代码质量由编写者负责
- Dify DSL 导入为尽力映射：不可映射项以警告清单提示人工补配，不保证复杂画布 1:1 还原
- 迭代子图与外层图各自独立计算超时预算（子图整体仍受节点数与 maxItems 约束）

## 存量环境知识库升级

旧版知识库环境：升级 Milvus（`docker compose -f mse-modules/mse-ai/docker/milvus/compose.yaml up -d`，镜像已升 2.6），重新构建后随服务启动自动应用最新版本化脚本（`db/mysql/`；历史手工方式为对照 jar 内版本化脚本对业务库补齐表结构），最后在知识库页面对各库点「重建索引」。

## 存量环境交互数据迁移（主库 → 独立库租户）

世界化改造前，全部 ai_* 行落在共享主库（tenant_id=世界编号）。独立库租户切换后读路由专属库，存量主库行不再可见——按下述模板把该租户的行幂等搬入其专属库（单实例跨库、主库执行；`<N>` = 租户编号、`<tenant_db>` = 专属库名；配置表主库行为权威，upsert 覆盖租户库种子副本；`ai_chat_message` 按 tenant_id 直搬）：

```sql
-- 交互五表 + 配置四表（对每张表执行，全列以主库为准覆盖）
INSERT INTO <tenant_db>.ai_chat_session    SELECT * FROM ai_chat_session    WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE title = VALUES(title), last_active_time = VALUES(last_active_time);
INSERT INTO <tenant_db>.ai_chat_message    SELECT * FROM ai_chat_message    WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE content = VALUES(content), ext = VALUES(ext);
INSERT INTO <tenant_db>.ai_usage_log       SELECT * FROM ai_usage_log       WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE status = VALUES(status);
INSERT INTO <tenant_db>.ai_tool_log        SELECT * FROM ai_tool_log        WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE result = VALUES(result);
INSERT INTO <tenant_db>.ai_workflow_run    SELECT * FROM ai_workflow_run    WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE answer = VALUES(answer);
INSERT INTO <tenant_db>.ai_agent           SELECT * FROM ai_agent           WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE name = VALUES(name), system_prompt = VALUES(system_prompt), status = VALUES(status);
INSERT INTO <tenant_db>.ai_model           SELECT * FROM ai_model           WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE name = VALUES(name), base_url = VALUES(base_url), api_key_enc = VALUES(api_key_enc), status = VALUES(status);
INSERT INTO <tenant_db>.ai_tool            SELECT * FROM ai_tool            WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE name = VALUES(name), config_json = VALUES(config_json), status = VALUES(status);
INSERT INTO <tenant_db>.ai_mcp_server      SELECT * FROM ai_mcp_server      WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE endpoint = VALUES(endpoint), status = VALUES(status);
INSERT INTO <tenant_db>.ai_api_key         SELECT * FROM ai_api_key         WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE key_hash = VALUES(key_hash), status = VALUES(status);
```

注意事项：

- 极小概率边缘：租户在主库新建的同码行（如 agent_code 与租户库种子副本同码不同 id）会撞唯一键 `uk(tenant_id, code, deleted)`——先按业务保留一方手工合并再执行。
- 旧格式开放 API appId（`ai-{hex}` 无世界段）在主库兜底期内仍可鉴权；独立库租户建议迁移后在「开放 API」页删除重建换发新格式（`ai-{tenantId}-{hex}`），随后可清理主库旧行。
- 验证无误后可选清理主库：`DELETE FROM ai_chat_session WHERE tenant_id = <N> AND tenant_id > 0;`（其余表同款；**确认租户库数据完整后再执行**）。
- 平台世界（tenant_id=0）管理端创建的模型/智能体等行在单体形态下已路由平台库；存量主库 0 行如需对齐可同款搬入平台库（`mse_platform`）。
- 平台「全局用量」视图读共享主库聚合，不含独立库租户专属库流水（跨库扇出聚合属后续课题）。

## 开放契约（业务能力自注册）

对话契约 `ai-api/RemoteAiChatService` 直接经 mse-open 开放平台对外：`AiOpenApiContributor` 在应用就绪后把 `POST /open/api/ai/chat`（授权码 `open:ai:chat`，target = `vip.mseapp.ai.api.RemoteAiChatService#chat`）推送至 mse-open 目录，凭证/授权/限流/审计全部复用开放平台，本工程无任何开放平台代码（仅一个描述 Bean）。
