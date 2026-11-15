# mse-data —— 多租户数据中台

独立业务工程（积木式组装，基座零改动），把企业系统脚手架（ai-mse）的用户/租户/权限底座延伸为**数据中台**：
数据源管理 → 数据集成同步（ODS 贴源层）→ 元数据/数据地图 → 数据服务 API → 数据质量。每个租户接入自己的数据源、发布自己的数据 API，行级隔离与 RBAC 全部继承基座。

## 一、能力一览

| 域 | 能力 |
|---|---|
| 数据源管理 | 注册外部业务库（MySQL / 达梦 / 金仓 / PostgreSQL），口令 AES-GCM 加密落库，连通性测试，独立 Hikari 连接池（闲置回收） |
| 数据集成 | 同步任务：整表 / 自定义 SELECT 查询 → 中台 ODS 贴源表（自动建表，附 tenant_id 列）；来源支持外部库与中台库自身（ODS→ODS 二次加工，仅限贴源表）；全量（清空重灌）/ 增量（时间戳/自增 ID 水位，重跑幂等）；cron 调度 + 手动执行；**前置任务依赖编排**（前置成功后级联触发，防环校验，链深上限 20）；运行日志审计 |
| 任务级血缘 | 同步任务自动登记「源表 → ODS」血缘，按表名双向查询（数据地图详情展示上下游） |
| 数据地图 | JDBC 元数据采集（表/列/注释），关键字检索，表详情与采样预览（限行数），**ODS 数据导出 xlsx（EasyExcel，上限 5 万行）** |
| 数据服务 | 把 SELECT 封装为开放 HTTP API（`/openapi/data/{path}`）：**发布审批流**（草稿→待审批→已发布/已驳回→下线，全程留痕，`mse.data.service.approval-enabled` 可关）、appKey + HMAC-SHA256 签名 + nonce 防重放、API 级授权（**批量授权 + 过期时间**：多选 API 一次授权，过期时间留空=永久；已授权的重复授权即刷新过期时间，列表支持单行改期、过期红色标识，开放调用到期即拒绝）、Redis 限流、超时/行数上限、调用审计；参数化绑定（`:name` → JDBC `?`），jsqlparser 校验仅 SELECT |
| 数据质量 | 规则类型：非空 / 唯一 / 数值区间 / 枚举 / 自定义坏数据 SQL / 行数波动（对比上次基线，阈值百分比）；cron 调度 + 手动执行；检测报告（总行数/坏数据/通过率） |
| AI 智能查询 | mse-ai 反向集成工具 `data_query`（`RemoteAiTool`，action 分发）：`meta_search` 检索表/列元数据（支持按**表名**查列清单）、`execute` 受控只读 SQL（SQL 防护 + 租户条件注入 + 限行数）、`datasource_list` 数据源清单（含白名单表清单 `allowedTables`）；所有编号以**字符串**回传（避免模型抄写长整型编号出错、前端 JSON 解析丢精度），查不到表时返回引导性错误（提示改用 keyword 重检）。中台库目录仅暴露可查面（ods_ + 白名单表），系统/凭证表结构对模型不可见 |
| 概览 | 资产计数、今日调用量、质量规则与未通过数、最近同步与调用记录 |

**安全模型**：
- 中台库（datasource_id=0）的数据服务允许 `ods_` 前缀贴源表 + **查询白名单业务表**（`dat_query_allowlist`，租户各自维护），两类表执行时自动注入 `tenant_id` 谓词（jsqlparser 改写，每张 JOIN 表都注入）——物理共享表、逻辑租户隔离；同步来源档仍仅限 `ods_` 贴源表 + 本库业务表（执行期探测）；
- 外部数据源不做行级注入（数据源本身归属租户，数据源级隔离）；同步源表若自带 tenant_id 列自动按当前租户过滤；
- ODS 写入清理严格带 `tenant_id` 条件，禁止 TRUNCATE。

## 二、工程结构（对齐 mse-oa / mse-mp 拼法）

```
mse-data/
├── pom.xml            # 父 pom：import mse-dependencies BOM（自补 postgresql 驱动版本仲裁）
├── data-service/      # 业务积木（纯 jar）：vip.mseapp.data
│   ├── config/        # DataProperties（mse.data.*）、DataCrypt（AES-GCM）、DataEngineConfig
│   ├── entity/        # dat_* 十四张表实体（继承 BaseEntity，行级租户隔离）
│   ├── mapper/        # MyBatis-Plus BaseMapper（无 XML）
│   ├── controller/    # /data/source /data/allow /data/sync /data/meta /data/api /data/apiapp /data/quality /data/overview
│   ├── service/       # 各域 Service（CRUD + 编排 + 审批流 + 血缘 + 质量）
│   ├── engine/        # 同步引擎：DataReader/DataWriter SPI（OLAP 预留点）+ JdbcReader/JdbcWriter
│   │                  #   + SyncEngine（级联依赖触发）/ SyncScheduler / QualityScheduler
│   ├── open/          # 开放 API：ApiQueryExecutor / OpenSignatureVerifier / OpenApiService / OpenApiController
│   └── support/       # DataConnectorManager（外部源连接池）、ApiSqlGuard（jsqlparser 防护）、TenantColumnProbe（租户列探测）
├── data-boot/         # 微服务启动器（8093）：micro-starter + data-service，application.yaml 接 Nacos，mse.rpc.mode 默认 dubbo
├── data-ui/           # 子应用（dev 3700，构建 /sub-apps/data/）：概览/数据源/集成/地图/服务/质量/查询白名单七页
├── docker/nacos/config/mse-data.yaml   # Nacos 服务专属配置留档（Data ID: mse-data.yaml）
└── data-service/src/main/resources/db/mysql/
    ├── V20261001_02_data__data_init.sql     # 全量初始化（14 业务表含 dat_query_allowlist；mse-target: master，启动自动应用）
    └── V20261001_01_data__data_base.sql     # 基座库接入（子应用注册 + 页面菜单 + 23 权限点，幂等；mse-target: base，starter-bridge 自动推送）
```

## 三、快速开始

前置：基座已 `mvn install -DskipTests`；MySQL/Redis/Nacos 可用（`docker compose up -d mysql redis nacos`）。

```bash
# 1. 初始化库表与权限点：免手工执行——业务表脚本（mse-target: master）随服务启动自动应用（mse.tenant.schema.auto-migrate=true，失败阻断启动）；
#    基座接入脚本（mse-target: base）由 starter-bridge 就绪即推 mse-system 按账本应用（周期重推自愈），重复推送无害。

# 2. 构建并启动后端（微服务形态：application.yaml（spring.config.import）接 Nacos 配置中心 + 注册）
cd mse-modules/mse-data
mvn install -DskipTests
CONFIG_DIR="$PWD/docker/nacos/config" sh ../../mse-framework/docker/nacos/init-configs.sh   # 首次：mse-data.yaml 导入 Nacos
java -jar data-boot/target/data-boot-1.0.0-SNAPSHOT.jar    # http://localhost:8093，注册进 Nacos（网关 lb://mse-data）

# 3. 启动前端（开发模式）
cd data-ui && npm install && npm run dev    # http://localhost:3700

# 4. 基座前端（mse-ui，3000 租户端）登录后即可见「数据中台」菜单（内置兜底注册表）
```

子应用生产接入：平台端「平台管理 → 子应用管理」新增（name=data，生产入口 `/sub-apps/data/`，可见端=租户端），`data-ui/dist` 部署到基座 nginx 的 `/sub-apps/data/` 路径。

## 四、数据服务 API 调用示例

```bash
# 签名：HMAC-SHA256(appSecret, appKey + "\n" + timestamp + "\n" + nonce) 十六进制小写
TS=$(date +%s); NONCE=$(uuidgen)
SIGN=$(python3 -c "import hmac,hashlib;print(hmac.new(b'$SECRET',f'$APPKEY\n$TS\n$NONCE'.encode(),hashlib.sha256).hexdigest())")

curl -X POST "http://localhost:8093/openapi/data/order-daily" \
  -H "X-App-Key: $APPKEY" -H "X-Timestamp: $TS" -H "X-Nonce: $NONCE" -H "X-Sign: $SIGN" \
  -H "Content-Type: application/json" \
  -d '{"params":{"status":"0"},"pageNum":1,"pageSize":20}'
# 返回：{"code":0,"message":"ok","data":{"columns":[...],"rows":[...],"hasMore":false,"costMs":12}}
```

审批流开启时（默认），API 创建后为草稿，需「提交审批 → 具备 `data:service:approve` 权限的角色审批通过」后方可被开放调用；已发布 API 一旦编辑自动打回草稿重新审批。

## 五、配置项（Nacos Data ID: mse-data.yaml，本地留档 docker/nacos/config/）

| 配置 | 默认 | 说明 |
|---|---|---|
| `mse.data.crypt-key` | 内置默认 | AES-GCM 密钥派生种子，**生产必须改** |
| `mse.data.pool.max-pool-size` | 8 | 每个注册数据源的连接池上限 |
| `mse.data.pool.idle-timeout-seconds` | 300 | 池闲置回收秒数 |
| `mse.data.sync.batch-size` | 2000 | 同步单批行数 |
| `mse.data.sync.executor-parallelism` | 2 | 同步执行线程池大小 |
| `mse.data.sync.tenant-concurrency` | 2 | 每租户同步/质量执行并发上限（防噪声邻居：满则本轮调度跳过，下轮扫描自愈） |
| `mse.data.open.signature-timeout-seconds` | 300 | 签名时间戳窗口 |
| `mse.data.open.default-per-minute` | 60 | API 未配置限流时的默认每分钟上限 |
| `mse.data.service.approval-enabled` | true | API 发布审批流开关（false = 保存即发布） |

信创切换：`--spring.profiles.active=dm|kingbase`（连接信息用 `MSE_DM_*` / `MSE_KB_*` 环境变量）。

## 六、取数通道与租户隔离

数据中台对「来源数据」分两条通道，隔离语义不同：

| 通道 | 标识 | 隔离语义 | 适用 |
| --- | --- | --- | --- |
| 内置中台库 | datasourceId=0（表单里的「中台库（内置 · 自动租户隔离）」） | 行级：自动按 `tenant_id` 过滤——ods_ 表与白名单业务表在 SQL 防护时静态注入租户条件；白名单业务表在目标库上探测须含 `tenant_id` 列（缺列/表不存在拒绝执行）；同步来源档业务表执行期探测含 `tenant_id` 列时强制只取本租户行（自定义查询结果必须带出 tenant_id 列，否则拒绝执行） | 共享库形态从平台主库取本租户业务数据（业务表 → ODS、ODS 二次加工）；白名单业务表供数据服务/AI 工具直查 |
| 外部数据源 | `dat_datasource` 登记（id>0） | 数据源级：仅本租户登记的数据源可用，不注入租户条件 | 租户自有库/外部业务库接入 |

**防呆护栏（MasterDbGuard）**：数据源登记、同步任务执行、数据服务/API 执行三处都会校验外部数据源坐标（host/port/dbName，localhost≈127.0.0.1 归一比对）是否命中平台主库，命中一律拒绝——防止把共享主库当「外部数据源」登记，绕过租户行级隔离。属提示性防线而非安全边界（真正的边界是主库凭据不外发），主库 JDBC URL 解析失败时放行不阻断。

**存量清理**：护栏上线前已登记的指向主库的数据源，执行期会被拒绝并给出明确提示；请删除该数据源，并把相关同步任务的来源改为内置「中台库」重建。

**中台库业务表元数据**：数据地图「登记中台库业务表」（`POST /data/meta/collect?datasourceId=0`）把主库业务表登记为 source 元数据（与贴源层 ods 分类互不干扰，差集清理按 数据源+分类 圈定），供同步任务表单的源表/水位列下拉使用；未登记时仍可手输表名。

**查询白名单（dat_query_allowlist）**：中台库通道（datasourceId=0）默认只放行 `ods_` 前缀贴源表；各租户可在「查询白名单」页面（子应用路由 `/allowlist`；后端接口 `/data/allow`，权限点 `data:allow:*`）把允许直查的中台库业务表登记进来（表名小写归一化、同表判重、启停开关），白名单为空 = 行为与仅 ods_ 时代完全一致。执行链路（数据服务 API / AI 工具 data_query 共用）先判定租户模式再选目标库：

| 租户模式 | 纯 ods_ 查询 | 白名单业务表查询 | ods_ + 白名单混查 |
| --- | --- | --- | --- |
| 共享库租户（ds_key 空） | 主库 | 主库 | 主库 |
| 独立库租户（ds_key 非空） | 主库（贴源表在主库） | **租户专属库**（业务数据在租户库） | 拒绝：提示拆分为两条查询 |

两类表均强制注入 `tenant_id` 条件；白名单表执行前在**目标库**上探测（共享库探主库、独立库探租户库），缺 tenant_id 列或表不存在时按模式给出明确报错，不放行。质量模块不扩展白名单（仍仅 ods_ 表、仍在主库执行），避免独立库租户在主库上跑出空数据的误导性报告。

**白名单硬黑名单约束**：登记/修改白名单时表名经 `mse.ai.biz-query.deny-*` 硬校验（`AiBizQueryProperties`，与各业务模块 biz 查询工具共用同一安全底线）——sys_/ai_/dat_ 等系统与凭证表即使手工登记也被 400 拒绝，防止白名单成为敏感数据的旁路。

**业务库查询已收敛至 mse-ai 模块化路由**：历史「业务库智能查询（biz_query 工具 / biz_analyst 智能体）」已从本工程移除——biz_query 现为 mse-ai 自持的**路由门面**（按模块注册表把查询转发到各业务模块自提供的查询工具，如 mse-oa `oa_biz_query`），查询在模块自身进程内用其自有租户路由连库执行（平台侧零业务库连接）；「业务分析师」（biz_analyst）通用入口智能体随之迁至 mse-ai。本工程仅保留 data_query（中台库 ods_ + 白名单表）与「数据中台智能分析师」（data_analyst，`DataAiAgentProvider` 声明）。模块接入范式详见 mse-ai README「业务模块化查询」一节。

**多工具 Dubbo 导出约定**：mse-ai 泛化调用按 interface+group 寻址，同一服务导出多个 `RemoteAiTool` 实现会被随机命中——多工具服务以**单一分发门面**导出（ai-api 共享件 `DispatchingAiTool`，两参 `invoke(toolCode, argumentsJson)` 按编码路由），工具描述仍由容器内各工具 Bean 经推送器逐个登记；单体聚合形态不受影响（mse-ai 直接收集容器内各 Bean）。其他业务服务接入多工具时照此模式。

## 七、全链路验证要点

1. 登录基座前端 → 数据中台 → 数据源管理：登记一个租户自有外部库 → 测试连通；若误填平台主库坐标（与中台库 host/port/库名一致），保存应被拒绝并提示改用内置「中台库」来源；
2. 数据集成正门：新建任务来源选「中台库（内置 · 自动租户隔离）」→ 源表选业务表（未登记可手输，如 sys_config）→ 目标 ods_sys_config，全量 → 立即执行 → 日志 success，且 ODS 仅含本租户行；自定义查询若把含租户列表的 tenant_id 投影掉，执行应报「查询结果必须带出 tenant_id 列」；
3. 数据地图「登记中台库业务表」→ 同步任务的源表/水位列下拉可见主库业务表；已登记的外部自有库任务不受影响（数据源级隔离）；
4. 依赖编排：新建任务 B（来源=中台贴源层 ods_sys_config → ods_sys_config_copy，前置任务=A）→ 手动执行 A → B 自动级联执行；把 A 的前置设为 B 应报「依赖成环」；
5. 数据地图「登记贴源层」→ 能看到 ods_sys_config / ods_sys_config_copy；表详情可见上下游血缘；「导出」下载 xlsx；
6. 数据质量：建 not_null 规则（ods_sys_config.config_key）执行通过；建 enum 规则（config_value ∈ dark/light）执行不通过；报告与最近结果正确回写；
7. 数据服务：新建 API（datasource=中台贴源层，SQL 带 `:key` 参数）→ 状态草稿 → 提交审批 → 审批通过 → 新建调用方应用（保存 AppKey/Secret）→ 授权该 API（多选批量 + 设过期时间；再对该授权「改期」验证开放调用到期返回 403「授权已过期」）→ 按「四」的示例开放调用成功，调用日志可见；编辑已发布 API 自动打回草稿；
8. 换一个租户账号重复，确认该租户看不到他租户的 API/应用/任务，且开放调用只返回本租户行。
9. 查询白名单：「查询白名单」页登记一张含 tenant_id 的中台库业务表 → 数据服务/AI 工具以 datasourceId=0 查询该表成功且仅返回本租户行；查询未登记表应报「仅允许 ods_ 前缀贴源表或白名单业务表」并列出白名单；把无 tenant_id 列的表登记进白名单，执行应报「缺少 tenant_id 列」拒绝；独立库租户混查 ods_ 与白名单表应报「请拆分为两条查询」；登记 sys_user 等敏感表应被白名单硬黑名单 400 拒绝。
10. 业务库智能查询：用 biz_analyst 智能体问业务统计（如本月各工单报工数量）→ meta_search 仅返回业务前缀表、SQL 自动注入 tenant_id 与 deleted=0 且仅本租户行；诱导查 sys_user / ai_chat_message 应报「禁止访问敏感表」；独立库租户执行应落其租户专属库；data_analyst 原链路（ods_ 查询 / 白名单表 / MES 问数场景 / 编排数据查询节点）回归不受影响。

## 八、演进路线

- **三期（OLAP 落地）**：DataWriter 扩展 ClickHouse/Doris 建 DWS 汇总层，数据服务可指定查询存储；实时接入（binlog/CDC）；指标/标签层。
- 其他候选：数据质量波动规则的多基线对比、血缘字段级展开、API 配额与计费、导出模板化。
- 跨进程契约：data-boot 已是 Dubbo 微服务（`mse.rpc.mode=dubbo`），自有契约可建 `data-api` 模块后 `@RpcService` 导出、消费方 `@RpcReference` 注入。

## 九、已知边界（二期）

- MySQL 流式读取用 `fetchSize=Integer.MIN_VALUE`；PG 协议族（PostgreSQL/金仓）以关闭自动提交换取游标流式（Hikari 归还时自动重置）。
- `:param` 占位不得出现在 SQL 字符串字面量内（不做字面量感知替换）。
- 中台库数据服务仅支持 ods_ 实体表、同步来源支持实体表直引（均拒绝 FROM 派生表/UNION/WITH）；复杂分析先落 ODS。
- 质量规则 range/enum 通过 SQL 直拼常量（数值/转义后字符串），不引入绑定参数；custom_sql 走 jsqlparser 防护。
- 调度器（同步/质量）为应用内存态（停机期间错过的时点只补跑一次）；分布式部署需外置调度。
- API 审批为租户内状态机（提交/审批权限分离），不含跨租户流程编排。


## 开放契约（业务能力自注册）

本工程把「调用已发布数据 API」开放给第三方，经 mse-open 开放平台统一对外（凭证/授权/限流/审计由开放平台完成）：

- `data-api`：`RemoteDataApiService.invoke` 契约（apiPath + params + 可选分页，JSON 进出）；
- `data-service`：`RemoteDataApiServiceImpl`（按已发布 API 复用 `ApiQueryExecutor` 受控执行：SQL 防护 + 租户谓词注入 + 行数/超时上限）+ `DataOpenApiContributor`；
- 应用就绪后自动推送 mse-open 目录（`open:data:api-invoke`），对外路径 `POST /open/api/data/api/invoke`。

与自持签名通道 `/openapi/data/**` 并存：后者走自建 appKey+HMAC 凭证体系；新接入建议统一走 mse-open 窗口。
## 开放凭证世界化（一切皆租户）

- `app_key` 新格式内嵌世界段 `dat-{tenantId}-{16hex}`：`/openapi/data/**` 自持签名通道无登录态，`OpenApiService.invoke` 解析世界段后 `runAs` 切到应用所属世界（独立库租户专属库 / 共享租户绑定库 / 平台库）读取 `dat_api_app` 校验（应用行/授权/审计此前即随会话世界写入，本变更补齐对齐的读侧）。
- 旧格式 app_key（`dat-{16hex}`）主库兜底（deprecated）：共享租户行为不变；独立库租户旧凭证请删除重建换发新格式。
- **存量迁移**（独立库租户，单实例跨库、主库执行，`<N>`=租户编号、`<tenant_db>`=专属库）：

```sql
INSERT INTO <tenant_db>.dat_api_app SELECT * FROM dat_api_app WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE app_secret = VALUES(app_secret), status = VALUES(status);
INSERT INTO <tenant_db>.dat_api_grant SELECT * FROM dat_api_grant WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE expire_time = VALUES(expire_time);
INSERT INTO <tenant_db>.dat_api_log SELECT * FROM dat_api_log WHERE tenant_id = <N> AND tenant_id > 0
  ON DUPLICATE KEY UPDATE cost_ms = VALUES(cost_ms);
```
