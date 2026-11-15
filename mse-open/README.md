# mse-open —— 业务中台（能力开放层）

基于 `ai-mse` 基座组装的**开放平台**：把基座的用户/部门/角色/租户能力以**应用凭证（appKey/appSecret）+ access_token** 的方式安全开放给其他系统调用，同时提供统一身份绑定、用户扩展属性与全量用户纳管能力。独立 Maven 工程，遵守 `AGENTS.md` 积木约定，**不修改基座任何代码**。

## 一、工程结构

```
mse-open/
├── open-api/       注册契约（纯 jar）：业务积木自注册入口（OpenApiContributor + RemoteOpenApiRegistry + 推送装配）
├── open-service/   开放平台业务积木（纯 jar）：应用/凭证/token/鉴权链/目录注册接收/泛化分发/开放 API/管理端
├── open-boot/      微服务启动器：micro-starter + open-service，端口 8092（application.yaml（spring.config.import）接 Nacos）
├── open-ui/        管理端子应用（wujie，dev 3600）：应用管理/调用日志/身份绑定/接入指南
├── docker/nacos/config/mse-open.yaml   Nacos 服务专属配置留档（Data ID: mse-open.yaml）
├── open-service/src/main/resources/db/mysql/   版本化迁移脚本（jar 内，MySQL）：
│   ├── V20261010_02_open__open_init.sql   业务表（app_key VARCHAR(64) 最终态）+ 内置 API 种子（mse-target: master，启动自动应用）
│   └── V20261001_01_open__open_base.sql   子应用注册+页面菜单+权限点（mse-target: base，starter-bridge 自动推送）
├── docs/           open-api-guide.md：业务接入与第三方调用详细指南（含完整示例）
```

## 二、快速开始

前置：基座已 `mvn install`；docker 的 MySQL/Redis/Nacos 已就绪（`docker compose up -d mysql redis nacos`）。

```bash
# 1. 建表 + 种子：免手工执行——业务表脚本（mse-target: master）随服务启动自动应用（mse.tenant.schema.auto-migrate=true，失败阻断启动）；
#    基座接入脚本（mse-target: base）由 starter-bridge 就绪即推 mse-system 按账本应用（周期重推自愈），重复推送无害。

# 2. 启动后端（微服务形态：application.yaml（spring.config.import）接 Nacos 配置中心 + 注册）
cd mse-modules/mse-open
mvn install -DskipTests
CONFIG_DIR="$PWD/docker/nacos/config" sh ../../mse-framework/docker/nacos/init-configs.sh   # 首次：mse-open.yaml 导入 Nacos
mvn spring-boot:run -pl open-boot          # 或 java -jar open-boot/target/open-boot-*.jar，注册进 Nacos（网关 lb://mse-open）

# 3. 启动管理端子应用（可选，也可走生产构建 + 后台「子应用管理」配置）
cd open-ui && npm install && npm run dev   # http://localhost:3600
```

前端接入：本地开发在基座 `mse-framework/mse-ui/src/micro/apps.ts` 兜底表已登记（平台端登录可见「开放平台」菜单）；生产走平台端「平台管理 → 子应用管理」配置（`sys_micro_app`：标识 `open`、生产入口 `/sub-apps/open/`、可见端=平台端）。

## 三、核心概念

| 概念 | 说明 |
| --- | --- |
| 接入应用（open_app） | 一个外部系统一条记录。appKey 自动生成；AppSecret **AES-GCM 加密落库，明文仅创建/重置时展示一次** |
| 业务绑定租户 | 应用必绑一个租户（0=平台域）。应用调用开放 API 时，全部数据访问被租户插件约束在该租户域内 |
| 开放 API（open_api） | 平台维护的 API 清单（种子数据 id 8001+）。应用只能调用被授权（open_app_api）的 API |
| access_token | `POST /open/oauth/token` 换取（client credentials 语义），默认 2h 有效；应用停用/删除/改绑租户**即时失效**（每次调用回查应用状态） |
| 管理端（/openapp/**） | 平台端 Sa-Token 会话 + `open:app:*` 等权限点控制，与开放 API 的机器凭证体系完全隔离 |

**鉴权链**（`OpenApiAuthInterceptor`，仅作用于 `/open/api/**`）：
Bearer token → 应用实时状态 → IP 白名单（`ip_whitelist`，空=不限制）→ 请求签名验签（`sign_required` 应用强制，见第八节）→ API 目录匹配（Ant 路径模板，最长模式优先）→ 应用授权校验 → 应用级限流（Redis 固定窗口，默认 QPS 见 `mse.open.default-rate-limit`）→ 注入租户上下文 → 异步审计落库（open_api_log）。

## 四、业务能力接入（业务积木自注册 + 泛化分发）

开放平台是**统一对外窗口**：凭证/目录/授权/限流/审计全部在此，业务积木只"声明我开放什么"。

| 角色 | 职责 |
| --- | --- |
| 业务积木（oa/mp/data/ai…） | 自有 `xxx-api` 契约（接口+DTO，机器语义）+ `@RpcService` 实现 + 一个 `OpenApiContributor` Bean（描述清单）；应用就绪后 open-api 装配自动推送注册（启动即注册 + 周期重推自愈，`mse.open.registry.interval-minutes` 可调，默认 5 分钟） |
| mse-open | 目录接收（**每租户一份目录，扇出播种**：推送按平台+全部租户逐世界 `runIgnore` + tenant_id 显式条件幂等 upsert（目录行驻业务主库、不随租户切库，按 tenant_id+code 定位，管理端 status/授权不被覆盖），单世界失败等下轮推送自愈；多实例重推按条目业务段 `open:<biz>:` 做窗口收敛 + 变更才写，一个窗口只处理一次）+ 鉴权链 + **泛化分发**：按目录 target（`契约接口全名#方法名`）dubbo 模式 GenericService 泛化调用 / 单体反射调用——**mse-open 不依赖任何业务 jar，业务新增开放能力 mse-open 零改动** |
| 第三方 | 只面对 `POST /open/oauth/token` + `/open/api/**`，与业务形态无关 |

**契约机器语义**：方法签名统一 `String 方法名(String requestJson)`（请求/返回 JSON 字符串，开放路径的 query/body/路径变量合并为请求 JSON）；操作者显式携带在请求内（如 `operatorUserId`，机器链路无登录态）；租户上下文由开放平台按应用绑定租户注入、经 RPC 隐式透传，契约不携带 tenantId。target 接口须落在 `mse.open.rpc.allowed-packages`（默认 `vip.mseapp.`）白名单内。

**目录治理**：`open_api.target` 为空 = 内置端点（本服务精确 Controller 承接）；非空 = 泛化分发。目录真租户化——**每会话世界一份目录**（租户会话读本租户目录行，平台会话/平台域凭证读平台世界行 tenant_id=0；业务库数据，`runIgnore` + 显式 tenant 条件读写，页面/分发/治理三方自洽）。存量平台目录行由 `V20261010_03_open__tenant_seed.sql` 每租户播种副本（`open_app_api` 授权引用同批改指副本），此后 rpc 条目经扇出播种持续维护。管理端「API 目录」页查本世界全量清单（来源 seed/rpc）与启停（`open:api:status`，**本世界行级**：租户端停用只影响本租户，平台端治理平台域凭证的目录）。业务积木未部署时调用返回 503/500（目录仍在，便于感知）。

首批已接入：mse-oa（流程全生命周期+Webhook）、mse-mp（粉丝/消息查询）、mse-data（调用已发布数据 API）、mse-ai（AI 对话）、mse-fs（文件上传/查询/下载/删除）。接入方法见 `AGENTS.md` 第四节「业务能力开放接入标准」。

> **详细使用示例**（业务六步接入 + 第三方调用 curl 全示例 + 管理端操作 + 双形态部署说明 + FAQ）见 [docs/open-api-guide.md](docs/open-api-guide.md)。

## 五、接入方对接指南

### 1. 换取访问凭证

```bash
curl -X POST http://localhost:8092/open/oauth/token \
  -H "Content-Type: application/json" \
  -d '{"appKey":"<你的AppKey>","appSecret":"<你的AppSecret>"}'
# → {"code":0,"data":{"accessToken":"...","expiresIn":7200,"tokenType":"Bearer"}}
```

### 2. 调用开放 API（清单节选）

| API 标识 | 方法 路径 | 说明 |
| --- | --- | --- |
| `open:user:page` | GET `/open/api/user/page` | 分页查询（自动按绑定租户过滤） |
| `open:user:get` / `get-by-username` | GET `/open/api/user/{id}`、`/by-username` | 查询用户 |
| `open:user:create` | POST `/open/api/user` | 创建用户（全量纳管；开放链路禁止创建超管） |
| `open:user:update` / `status` / `reset-password` / `delete` | PUT/DELETE | 全生命周期维护 |
| `open:user:role-ids`（GET/PUT） | | 查询/覆盖式分配角色 |
| `open:user:extend-get` / `extend-put` | | 用户扩展属性（JSON 对象整体读写，≤4KB） |
| `open:dept:tree` / `create` / `update` / `delete` | | 组织架构同步 |
| `open:role:list` / `create` | | 角色查询/基础创建（授权矩阵仍由管理端维护） |
| `open:bind:create` / `resolve` / `by-user` / `delete` | | 统一身份绑定（外部标识 ↔ 中台账号） |

```bash
# 创建用户
curl -X POST http://localhost:8092/open/api/user \
  -H "Authorization: Bearer {accessToken}" -H "Content-Type: application/json" \
  -d '{"username":"oa_user01","password":"pass123456","nickname":"OA用户","roleIds":[]}'

# 身份绑定：外部系统用户 ↔ 中台账号
curl -X POST http://localhost:8092/open/api/bind \
  -H "Authorization: Bearer {accessToken}" -H "Content-Type: application/json" \
  -d '{"userId":1001,"sourceSystem":"oa","externalUserId":"emp-8801"}'

# 外部标识反查中台账号（SSO/会话映射基础）
curl "http://localhost:8092/open/api/bind/resolve?sourceSystem=oa&externalUserId=emp-8801" \
  -H "Authorization: Bearer {accessToken}"

# OA 流程引擎复用：表单/流程图设计→部署→发起（业务键）→审批→历史→Webhook 回调全生命周期
curl -X POST http://localhost:8092/open/api/oa/process \
  -H "Authorization: Bearer {accessToken}" -H "Content-Type: application/json" \
  -d '{"modelId":2001,"businessKey":"PO-2026-0001","dataValues":"{\"field_amount\":158000}","summary":"服务器扩容采购","operatorUserId":1}'
```

> 除上表内置端点外，各业务积木经自注册推送的开放 API 同样在此鉴权/分发，例如 OA 的 `open:oa:form-*`（表单管理）、`open:oa:model-*`（流程图管理）、`open:oa:process-*`（发起/查询/审批动作/历史）、`open:oa:todo-page`——**对接文档见 [mse-oa/OPEN-API.md](../mse-oa/OPEN-API.md)**，管理端「API 目录」可查全量。

### 3. 错误语义

| 状态码 | 含义 |
| --- | --- |
| 401 | 凭证缺失/无效/过期，或应用停用、删除、凭证过期 |
| 403 | 应用未授权调用该 API（到管理端「授权」勾选） |
| 404 | API 未开放（路径/方法不在 open_api 清单） |
| 429 | 超出应用限流 QPS |

## 六、全链路验证脚本要点（人工回归）

前置：库表已初始化，open-boot 已启动，平台端已登录（platform/admin123）。

1. **管理端建应用**：开放平台 → 应用管理 → 接入应用（绑定租户 1），保存弹窗中的 AppKey/AppSecret；
2. **授权 API**：至少勾选 `open:user:page`、`open:user:create`、`open:bind:create`、`open:bind:resolve`；
3. **错误凭证 401**：错误 secret 换 token → 401；无 Authorization 调开放 API → 401；
4. **正常链路**：换 token → `/open/api/user/page` 只返回租户 1 的用户（租户隔离生效）；
5. **未授权 403**：用只授权了 page 的应用调 `open:user:create` → 403；
6. **未开放 404**：调清单外路径（如 `GET /open/api/role/xxx`）→ 404；
7. **限流 429**：应用限流 QPS 配为 1，连续调用 ≥2 次 → 429；
8. **绑定往返**：bind 创建 → resolve 反查 → by-user 列表 → 重复 bind 同一外部标识 → 400；
9. **扩展属性**：put 扩展属性 → get 读回一致；超 4KB → 400；
10. **业务自注册**：启动聚合了业务积木的宿主（mse-monolith，或微服务形态 oa-boot/open-boot）→「API 目录」页出现
    source=rpc 条目（open:oa:*、open:mp:*、open:data:*、open:ai:*）→ 应用授权后调
    `POST /open/api/ai/chat`（body: agentCode/message）与 `POST /open/api/oa/process`
    （body: modelId/businessKey/dataValues/summary/operatorUserId）→ 租户域内生效、审计落库；
    「API 目录」停用某条 → 立即 404；未授权 → 403；
11. **审计**：管理端「调用日志」可见上述全部调用（含 401/403/429 与耗时/IP/TraceId）；
12. **权限点 403**：未授予 `open:app:list` 的平台账号访问管理端 → 403（权限点挂靠平台端页面菜单，
    平台超管域内全量；其他平台账号经「角色管理 → 授权」勾选 `open:app:*` 权限点）。

## 七、设计边界与后续规划

- **一期（本期）**：REST 开放 API（内部接入标准）+ 应用/凭证/授权/限流/审计 + 管理端 + 业务积木自注册与泛化分发（业务能力接入，mse-open 零改动扩展）。
- **二期（已交付）**：请求签名验签（HMAC-SHA256 + 时间戳防重放 + nonce，Redis 去重）与 IP 白名单（精确 IP / IPv4 CIDR）——应用级 `sign_required` / `ip_whitelist` 开关，管理端「应用管理」维护，字段在 jar 内版本化脚本 `db/mysql/V20261010_02_open__open_init.sql`；**SSO 授权码模式**为规划项（需对基座登录页做一处最小增强：支持回跳参数，届时单独评审）。
- **三期**：Webhook 事件订阅推送（事件边界 = 经开放 API 的变更，不改基座）+ `open-sdk` Java 客户端；内网 Java 系统 RPC 直调可直接引基座 `api-system` 契约 + `@RpcReference`，无需本工程。
- **微服务形态（网关接入）**：已就绪且为默认形态。open-boot 经 `application.yaml`（spring.config.import）接 Nacos 配置中心并注册（`mse.rpc.mode=dubbo`，`@RpcReference` 经 Nacos 注册中心消费基座契约）。Nacos `mse-gateway.yaml` 已配 `/open/** → lb://mse-open` 路由，白名单（`mse.gateway.ignore-urls`）已放行 `/open/oauth/token`（公开换凭证端点）与 `/open/api/**`（应用凭证在下游 open-service 校验，纵深防御）。
- **安全基线**：生产必须更换 `mse.open.crypt-key`（更换后历史密文不可解，需重置各应用密钥）；凭证过期时间与限流 QPS 按应用分别设置；对外暴露的应用建议开启请求签名并配置 IP 白名单。

## 八、请求签名（外部第三方标准，可选启用）

应用开启 `sign_required` 后，`/open/api/**` 全部请求在 Bearer token 之外须附加三个签名头（`mse.open.sign-timestamp-window-seconds` 默认 300s）：

| 请求头 | 说明 |
| --- | --- |
| `X-Open-Timestamp` | 毫秒时间戳，与服务端偏差超窗口即拒绝 |
| `X-Open-Nonce` | 一次性随机串（≤128 字符），Redis 去重（TTL=窗口+余量），多实例共享防重放 |
| `X-Open-Signature` | HMAC-SHA256 十六进制小写 |

**签名原文**（`\n` 连接，密钥 = AppSecret 明文）：

```
appKey
HTTP方法（大写）
路径[?原始query]
X-Open-Timestamp
X-Open-Nonce
sha256Hex(请求体)      # 无请求体 = 空串的摘要
```

服务端常量时间比对；GET 的 query 按原始串参与签名（不做重排）。超窗口先校时钟，重放请求直接 401 且记审计。
## 凭证世界化（一切皆租户）

- `app_key` 新格式内嵌世界段 `mse-{tenantId}-{16hex}`：换凭证通道（`POST /open/oauth/token`）无登录态，解析世界段后 `runAs` 切到应用所属世界（独立库租户专属库 / 共享租户绑定库 / 平台库）读取 `open_app` 校验；`checkCredential`/`domainAllowed` RPC 同款。
- 应用/授权（`open_app`/`open_app_api`）行随绑定租户世界驻留：管理侧（租户会话）随世界路由读写，平台会话全局列表读共享主库（不含独立库租户专属库应用）；跨世界改绑不支持（删除重建即换发）。
- 拦截器实时状态回查按 token 会话的租户快照路由读取；授权计数随应用世界路由。
- 调用审计（`open_api_log`）随世界路由落库：`openApiLogExecutor` 经 TaskDecorator 捕获租户 + 行上显式 tenantId 双保险；平台全局日志视图读共享主库（不含独立库租户专属库流水）。
- **存量迁移**（独立库租户，单实例跨库、主库执行，`<N>`=租户编号、`<tenant_db>`=专属库）：

```sql
INSERT INTO <tenant_db>.open_app SELECT * FROM open_app WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE name = VALUES(name), status = VALUES(status);
INSERT INTO <tenant_db>.open_app_api SELECT * FROM open_app_api WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE api_id = VALUES(api_id);
INSERT INTO <tenant_db>.open_api_log SELECT * FROM open_api_log WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE status_code = VALUES(status_code);
```

  旧格式 app_key（24 位十六进制）主库兜底期内可继续换 token；独立库租户建议重置密钥/重建应用换发 `mse-{tenantId}-…` 新格式后清理主库旧行。
