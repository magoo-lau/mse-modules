# 开放平台使用指南（业务积木接入 + 第三方调用）

本文是 mse-open 开放平台的**详细使用说明**：业务开发者如何把自己的能力接入开放平台（自注册 + 泛化分发），接入方（第三方系统）如何调用开放 API。机制概述见 [README](../README.md) 第四节，本文展开到可照抄的程度。

---

## 一、总体架构

```
第三方系统                        mse-open（统一对外窗口）                    业务积木（能力提供方）
    │                                    │                                        │
    │ ① POST /open/oauth/token           │                                        │
    │    (appKey+appSecret)              │                                        │
    │ ◄── access_token(2h, Redis) ────── │                                        │
    │                                    │                                        │
    │ ② POST /open/api/oa/process        │                                        │
    │    (Bearer token + 业务参数) ─────► │ 鉴权链：token→应用状态→IP白名单→       │
    │                                    │ 签名验签→目录匹配→授权→限流→          │
    │                                    │ 注入租户→异步审计                     │
    │                                    │                                        │
    │                                    │ ③ 按目录 target 泛化分发                │
    │                                    │    dubbo: GenericService 泛化调用 ─────►│ @RpcService 契约实现
    │                                    │    local: 容器反射直调            ─────►│ （租户上下文随 RPC 透传）
    │ ◄── Result{code,message,data} ──── │ ◄──────────── JSON 字符串 ─────────────│
```

三个角色各管一段，**业务新增开放能力不需要改 mse-open 任何代码**：

| 角色 | 职责 | 改动时机 |
| --- | --- | --- |
| 业务积木 | 声明开放什么：`xxx-api` 契约 + `@RpcService` 实现 + `OpenApiContributor` 描述清单 | 每次新增开放能力只动自己工程 |
| mse-open | 目录接收（幂等 upsert）、凭证/授权/限流/审计、泛化分发 | 仅机制演进时改 |
| 第三方 | 换 token → 按目录调 `/open/api/**` | — |

---

## 二、业务积木接入指南（六步，以 mse-oa 为参考实现）

### 第 1 步：创建 `xxx-api` 契约模块

契约模块只放**接口 + Serializable DTO**，依赖仅 `common-core`（参考 `mse-modules/mse-oa/oa-api/pom.xml`）：

```xml
<dependencies>
    <dependency>
        <groupId>vip.mseapp</groupId>
        <artifactId>common-core</artifactId>
    </dependency>
</dependencies>
```

**契约方法签名统一为 `String 方法名(String requestJson)`**——这是泛化分发的关键约定：dubbo 泛化调用按 `java.lang.String` 单参数透传，单体反射按 `getMethod(方法名, String.class)` 定位，开放路径的 query/body/路径变量合并成请求 JSON 整体传入，返回值约定为 JSON 字符串。

```java
public interface RemoteOaProcessService {

    /** 发起流程。requestJson = OaProcessStartRequest JSON，返回 OaProcessStartResult JSON */
    String startProcess(String requestJson);

    /** 查询流程详情。requestJson = OaProcessQueryRequest JSON */
    String getProcessDetail(String requestJson);

    /** 查询待办分页。requestJson = OaTodoQueryRequest JSON */
    String pageTodo(String requestJson);
}
```

**机器语义 DTO 规则**（参考 `oa-api/src/main/java/vip/mseapp/oa/api/dto/`）：

- 请求 DTO 字段 = 对外入参：显式携带操作者（如 `operatorUserId`，机器凭证链路**没有登录态**，不能依赖 `UserContext`）；分页参数带默认值（`pageNum=1`、`pageSize=10`）；
- **不要携带 tenantId**——租户上下文由开放平台按应用绑定租户注入，经 RPC（Dubbo attachment / 单体线程上下文）隐式透传；
- 返回 DTO 是**对外稳定子集**：只暴露第三方需要的字段，不外泄内部实体（如 `OaProcessDetailResult` 而不是直接返回 `ProcessDetail`）。

### 第 2 步：service 模块写 `@RpcService` 契约实现

放业务 service 模块独立包（如 `vip.mseapp.oa.bpm.rpc`），**不要标 `@Component`/`@Service`**，由自动装配以 `@Bean + @ConditionalOnMissingBean` 注册（保持"可整体替换"语义，与 mse-ai 同款）：

```java
@RpcService(RemoteOaProcessService.class)
public class RemoteOaProcessServiceImpl implements RemoteOaProcessService {

    @Override
    public String startProcess(String requestJson) {
        OaProcessStartRequest request = parse(requestJson, OaProcessStartRequest.class);
        // 校验显式操作者，然后桥接登录态复用现有服务逻辑
        String instanceId = asUser(request.getOperatorUserId(),
                () -> processService.start(new OaProcessService.StartRequest(
                        request.getModelId(), request.getDataValues(), request.getSummary(), null)));
        OaProcessStartResult result = new OaProcessStartResult();
        result.setProcessInstanceId(instanceId);
        return toJson(result);
    }

    /** 机器链路桥接：显式操作者 → UserContext（现有服务无感复用），finally 清理防串号 */
    private <T> T asUser(Long operatorUserId, Supplier<T> action) {
        UserContext.set(new UserContext.UserInfo(operatorUserId, "open-api", false));
        try {
            return action.get();
        } finally {
            UserContext.clear();
        }
    }
}
```

要点：

| 规则 | 原因 |
| --- | --- |
| 失败抛 `ServiceException(code, message)`（common-core） | 双方 classpath 都有该类，dubbo 可透传；`message` 即对外错误说明，写"人话" |
| 入参 JSON 解析失败 → `ServiceException(400, ...)` | 开放平台原样透传给第三方 |
| 内部服务依赖登录态时用 try/finally 桥接 `UserContext`，不改内部服务签名 | 最小侵入 |
| 返回 JSON 解析失败兜底 500 | 泛化链路的响应约定 |

### 第 3 步：写 `OpenApiContributor` 声明开放清单

```java
public class OaOpenApiContributor implements OpenApiContributor {

    private static final String PROCESS_CONTRACT = "vip.mseapp.oa.api.RemoteOaProcessService";

    @Override
    public List<OpenApiDescriptor> descriptors() {
        return List.of(
                descriptor("open:oa:process-start", "发起流程", "POST", "/open/api/oa/process",
                        PROCESS_CONTRACT + "#startProcess",
                        "body: modelId/businessKey/dataValues/summary/operatorUserId；返回 processInstanceId"),
                descriptor("open:oa:process-detail", "查询流程详情", "GET", "/open/api/oa/process/{processInstanceId}",
                        PROCESS_CONTRACT + "#getProcessDetail", "按流程实例编号查状态与当前节点"));
    }
}
```

`OpenApiDescriptor` 各字段要求（注册时逐条校验，不合法条目告警跳过、不拖垮整批）：

| 字段 | 要求 |
| --- | --- |
| `code` | `open:<biz>:<resource>[-<action>]` 格式，全局唯一（授权与审计锚点） |
| `name` | 管理端与接入指南展示名 |
| `path` | 以 `/open/api/` 开头 + **业务前缀段**（`/open/api/oa/...`、`/open/api/mp/...`），支持 Ant 变量段 `{id}`；勿与内置路径（`/open/api/user|dept|role|bind/**`）冲突 |
| `httpMethod` | `GET` / `POST` / `PUT` / `DELETE`（大写） |
| `target` | `契约接口全名#方法名`，接口须落在 `mse.open.rpc.allowed-packages` 白名单（默认 `vip.mseapp.`），方法必须是 `String(String)` 签名 |
| `description` | 参数说明，落目录 remark，接入方在「API 目录」页可见 |

### 第 4 步：自动装配注册（service 模块 AutoConfiguration）

```java
@AutoConfiguration(before = MseMybatisAutoConfiguration.class)
@MapperScan(basePackages = "vip.mseapp.mp.mapper", annotationClass = Mapper.class)
@ComponentScan(basePackages = {"vip.mseapp.mp.config", "vip.mseapp.mp.service"})
public class MpServiceAutoConfiguration {

    /** 契约实现：可声明同契约 Bean 整体替换 */
    @Bean
    @ConditionalOnMissingBean(RemoteMpFanService.class)
    public RemoteMpFanService remoteMpFanService(MpFanService fanService, JsonMapper objectMapper) {
        return new RemoteMpFanServiceImpl(fanService, objectMapper);
    }

    /** 开放能力声明：按 Bean 名判重——多积木各声明各的 Contributor，互不让位 */
    @Bean(name = "mpOpenApiContributor")
    @ConditionalOnMissingBean(name = "mpOpenApiContributor")
    public OpenApiContributor mpOpenApiContributor() {
        return new MpOpenApiContributor();
    }
}
```

> Contributor 的判重条件必须用**按 Bean 名**（`@ConditionalOnMissingBean(name = "...")`），不能用按类型——单体聚合多个业务积木时，按类型会让后装配的积木整体让位，只注册上第一个。

### 第 5 步：pom 依赖

业务 service 模块加两个依赖（版本在各工程父 pom `dependencyManagement` 管理，`open-api` 显式 `1.0.0-SNAPSHOT`）：

```xml
<dependency>
    <groupId>vip.mseapp</groupId>
    <artifactId>mp-api</artifactId>      <!-- 本工程契约 -->
</dependency>
<dependency>
    <groupId>vip.mseapp</groupId>
    <artifactId>open-api</artifactId>    <!-- mse-open 注册契约（OpenApiContributor + 推送装配） -->
</dependency>
```

### 第 6 步：验证接入

1. 构建：`mse-open` 先 `mvn install`（发布 open-api）→ 本工程 `mvn install`；
2. 启动宿主（mse-monolith，或微服务形态同时起 open-boot 与 xxx-boot）；
3. 平台端登录 → 开放平台 → **「API 目录」**页：出现本工程条目，来源列 = `自注册`（source=rpc），分发目标列 = target；
4. 应用管理 → 授权 → 勾选新条目 → 用应用凭证调通（见第三节）；
5. 故意写错一条描述（如 target 方法名拼错）→ 重启 → 该条被跳过且日志有告警，其余条目正常注册。

### 接入核对清单

- [ ] 契约方法签名是 `String xxx(String requestJson)`
- [ ] 请求 DTO 显式携带操作者、无 tenantId 字段
- [ ] 实现抛 `ServiceException(code, message)`、message 面向第三方可读
- [ ] 实现未标 `@Component`，由 AutoConfiguration `@Bean + @ConditionalOnMissingBean` 注册
- [ ] Contributor 按 Bean 名判重；code/path/target 符合第 3 步规范
- [ ] 「API 目录」页看到 `自注册` 条目并授权后，curl 调通

---

## 三、第三方调用指南（接入方视角）

### 1. 换取访问凭证

```bash
curl -X POST http://localhost:8092/open/oauth/token \
  -H "Content-Type: application/json" \
  -d '{"appKey":"你的AppKey","appSecret":"你的AppSecret"}'
# → {"code":0,"data":{"accessToken":"...","expiresIn":7200,"tokenType":"Bearer"}}
```

凭证有效期默认 2 小时，**请缓存复用**（到期前刷新）；应用停用/删除/改绑租户即时失效。

### 2. 调用开放 API

所有请求携带 `Authorization: Bearer {accessToken}`。参数合并规则：**query 参数 < body JSON 字段 < 路径模板变量**（同名时后者覆盖前者），合并后作为请求 JSON 传给业务契约。

当前已开放能力一览（实时清单以管理端「API 目录」页为准）：

| 域 | 授权码 | 方法 路径 | 说明 |
| --- | --- | --- | --- |
| 基座 | `open:user:*` 等 22 条 | `/open/api/user\|dept\|role\|bind/**` | 用户/部门/角色/身份绑定（内置端点） |
| OA | `open:oa:process-start` | `POST /open/api/oa/process` | 发起流程 |
| OA | `open:oa:process-detail` | `GET /open/api/oa/process/{processInstanceId}` | 流程状态/当前节点 |
| OA | `open:oa:todo-page` | `GET /open/api/oa/todo` | 指定人待办分页 |
| MP | `open:mp:fans-page` | `GET /open/api/mp/fans` | 粉丝分页（只读） |
| MP | `open:mp:messages-page` | `GET /open/api/mp/messages` | 消息/事件流水（只读） |
| 数据 | `open:data:api-invoke` | `POST /open/api/data/api/invoke` | 调用已发布数据 API |
| AI | `open:ai:chat` | `POST /open/api/ai/chat` | 调用已发布智能体对话 |
| 文件 | `open:fs:file-*` 4 条 | `POST /open/api/fs/file/upload`、`GET /open/api/fs/file/{fileId}`、`POST /open/api/fs/file/{fileId}/download`、`DELETE /open/api/fs/file/{fileId}` | 文件上传/元数据/下载/删除（mse-fs 自注册） |

**示例 1：发起 OA 流程**（`operatorUserId` 为租户内发起人）：

```bash
curl -X POST http://localhost:8092/open/api/oa/process \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"modelId":1,"businessKey":"ERP-2026-0001","dataValues":"{\"amount\":8800}","summary":"对账单审批","operatorUserId":1001}'
# → {"code":0,"data":{"processInstanceId":"50001","businessKey":"ERP-2026-0001"}}

# 查询流程推进状态
curl "http://localhost:8092/open/api/oa/process/50001" -H "Authorization: Bearer $TOKEN"

# 拉取待办（外部系统做代办提醒）
curl "http://localhost:8092/open/api/oa/todo?operatorUserId=1001&pageNum=1&pageSize=10" \
  -H "Authorization: Bearer $TOKEN"
```

**示例 2：查询公众号粉丝与消息**：

```bash
curl "http://localhost:8092/open/api/mp/fans?subscribed=1&pageNum=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
curl "http://localhost:8092/open/api/mp/messages?direction=in&kind=message&pageNum=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

**示例 3：调用数据中台已发布 API**（`apiPath` 是数据服务里审批发布过的 API 路径；受控通道自动叠加 SQL 防护 + 租户谓词 + 行数上限）：

```bash
curl -X POST http://localhost:8092/open/api/data/api/invoke \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"apiPath":"/cust/orders","params":{"status":"PAID"},"pageNum":1,"pageSize":20}'
# → {"code":0,"data":{"columns":[...],"rows":[...],"hasMore":true,"costMs":35}}
```

**示例 4：AI 对话**（`agentCode` 为已发布智能体编码）：

```bash
curl -X POST http://localhost:8092/open/api/ai/chat \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"agentCode":"assistant","message":"汇总本月订单","variables":{"month":"2026-09"}}'
```

### 3. 响应结构与错误语义

两层错误语义，接入方先看 HTTP 状态码再看 body code：

**鉴权链拒绝 → 真实 HTTP 状态码**（开放平台拦截器直写，body 亦带同义 code）：

| HTTP 状态码 | 含义 | 处理建议 |
| --- | --- | --- |
| 401 | 凭证缺失/无效/过期，或应用停用/删除/过期 | 重新换 token；持续 401 检查应用状态 |
| 403 | 应用未授权调用该 API | 管理员在「应用管理 → 授权」勾选 |
| 404 | API 未开放（不在目录/已停用）或路径不存在 | 「API 目录」页核对 method+path |
| 429 | 超出应用限流 QPS | 退避重试或申请调高 QPS |

**业务层结果 → HTTP 200 + body code 非 0**（契约异常经统一异常处理器透传，判定成功恒为 HTTP 200 + `code=0`）：

| body code | 含义 | 处理建议 |
| --- | --- | --- |
| 400 | 业务参数不合法（契约校验/JSON 解析失败） | 按 message 修正参数 |
| 404 | 业务资源不存在（如流程实例、数据 API） | 核对资源编号/路径 |
| 500 / 503 | 业务执行失败 / 目标业务服务不可用（泛化分发） | 503 多为目标服务未部署，稍后重试 |

其余未捕获系统异常 → HTTP 500。**判定成功请以 `HTTP 200 && body.code == 0` 为准。**

### 4. 接入方最佳实践

- **token 缓存复用**：按 `expiresIn` 提前刷新，避免每请求换 token（换 token 不限流但增加延迟）；
- **幂等业务键**：发起流程类操作携带自己的 `businessKey`，失败重试前先查详情防重复发起；
- **分页上限**：查询类 pageSize 上限 100（data 通道 200），全量同步请翻页拉取；
- **租户隔离**：应用绑定租户即数据域，一切读写被约束在该租户内，无需（也无法）传租户参数；
- **可选安全增强（应用开启后强制）**：管理员在「应用管理」开启强制验签（`sign_required`）后，每个请求须附三个头——`X-Open-Timestamp`（毫秒时间戳，±300s 窗口）、`X-Open-Nonce`（≤128 字符一次性随机串，窗口内防重放）、`X-Open-Signature`（HMAC-SHA256 十六进制小写，密钥=AppSecret 明文；签名原文以 `
` 连接：appKey、HTTP 方法大写、`path[?原始query]`、timestamp、nonce、`sha256Hex(body)`，无请求体时空串摘要）；配置 IP 白名单（精确 IP / IPv4 CIDR，空=不限制）后来源 IP 必须命中。细节与样例见 mse-open/README 第八节。

---

## 四、管理端操作（平台管理员）

| 页面 | 操作 | 说明 |
| --- | --- | --- |
| 应用管理 | 接入应用 / 授权 / 启停 / 限流 QPS / 重置密钥 | 授权弹窗勾选 API（含自注册条目）；AppSecret 仅创建/重置时展示一次 |
| API 目录 | 查看 / 启停 | 来源列区分 `内置`(seed) 与 `自注册`(rpc)；停用**即时全平台下线**（目录缓存失效）；定义字段由业务重推维护，管理端只做治理 |
| 调用日志 | 审计检索 | 全部调用（含 401/403/404/429/500/503）记录应用/API/耗时/IP/TraceId |
| 身份绑定 | 外部标识 ↔ 中台账号 | SSO/会话映射基础，跨租户只读 |

---

## 五、部署形态说明

| 形态 | 注册通道 | 分发通道 | 说明 |
| --- | --- | --- | --- |
| 单体（mse-monolith） | 容器内直调 `RemoteOpenApiRegistry`（聚合了 open-service 即存在） | 容器反射调用契约实现（业务 api jar 在 classpath） | 无中间件依赖；宿主未聚合 open-service 时推送器告警一次后闲置 |
| 微服务（xxx-boot + open-boot） | dubbo 泛化引用远调 mse-open（`check=false` 启动不依赖对方就绪，未就绪自动重试） | Dubbo `GenericService` 泛化调用（mse-open **不依赖业务 jar**） | 租户上下文经 `MseDubboContextFilter` attachment 透传，provider 端自动恢复 |

注册周期：应用就绪即推送 + 每 `mse.open.registry.interval-minutes`（默认 5 分钟）重推自愈（mse-open 重启/目录丢失后自动补注册）。可用 `mse.open.registry.enabled=false` 关闭某服务的自注册推送。

---

## 六、边界与 FAQ

**Q：目录条目会被重复注册吗？**
不会。按 `code` 幂等 upsert：已存在且定义未变则跳过；定义变了（如 path 调整）更新定义字段；管理端启停状态（status）与授权关系（open_app_api）永不被注册覆盖。

**Q：两家业务都注册了同一个 code？**
按 code 唯一，后注册者更新前者的定义。请遵守 `open:<biz>:<resource>` 命名规范避免冲突。

**Q：target 指向了非契约方法（如内部管理接口）？**
注册时被白名单（`mse.open.rpc.allowed-packages`，默认 `vip.mseapp.`）拦截；分发时再校验一次。要开放新包前缀在 mse-open 的 Nacos 配置里追加。

**Q：业务服务没启动，第三方调用会怎样？**
目录条目仍在（感知 + 授权不受影响），调用返回 500/503 与明确 message；服务恢复后自动可用，无需 mse-open 重启。

**Q：停用与未授权的区别？**
停用（目录级，`open:api:status`）= 全平台所有应用立即 404；未授权（应用级）= 该应用 403，其他应用不受影响。

**Q：为什么返回值约定 JSON 字符串而不是 DTO 对象？**
dubbo 泛化调用按方法签名 `String(String)` 零 POJO 转换透传（mse-open 不需要业务 DTO 在 classpath）；单体反射同理。类型安全由业务侧 DTO 承担（契约 jar 在业务自己手里），跨服务边界只走 JSON。

**Q：mse-data/mse-ai 自有的 `/openapi/data`、`/openapi/ai` 是什么？**
历史"业务自持开放端点"（自带 appKey+HMAC 签名体系），与开放平台并存；新接入一律走 mse-open 统一窗口，存量通道后续另行收敛。

---

## 附：参考实现索引

| 内容 | 位置 |
| --- | --- |
| 契约模块样板 | `mse-modules/mse-oa/oa-api/`（接口 + dto）、`mse-modules/mse-ai/ai-api/`（基座 BOM 外 biz 契约先例） |
| 契约实现 + 登录态桥接 | `mse-modules/mse-oa/oa-bpm/.../rpc/RemoteOaProcessServiceImpl.java` |
| Contributor 声明 | `mse-modules/mse-oa/oa-bpm/.../openapi/OaOpenApiContributor.java`、mse-mp/mse-data/mse-ai 同名包 |
| 注册接收端 | `mse-modules/mse-open/open-service/.../service/OpenApiRegistryService.java`（校验/upsert/启停） |
| 泛化分发 | `mse-modules/mse-open/open-service/.../dispatch/`（宽 controller + dubbo/local 双通道） |
| 自注册推送 | `mse-modules/mse-open/open-api/.../support/OpenApiRegistrationPusher.java` |
| 只加 Contributor 的最简接入 | `mse-modules/mse-ai/ai-service/.../openapi/AiOpenApiContributor.java` |
