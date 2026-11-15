# mse-search 搜索引擎服务

企业级全文搜索引擎（mse-modules 业务工程，独立 Maven 构建）：**Elasticsearch 9.x** 引擎 + **可搜索源自注册（解耦）** + **目录/扫描/索引全租户归属（每租户一份目录、每租户 × 每源一个索引）** + **三层权限过滤（租户隔离 / 来源可见性（权限码 + 可见角色）/ 个人数据范围）** + **定时增量扫描分片** + **MQ 实时事件** + **闲时任务窗口**。

## 一、总体架构

```
业务工程（oa/mp/data/...）                mse-search                        基座
┌──────────────────────┐    RPC 拉取   ┌──────────────────────┐   过滤查询   ┌─────────┐
│ search-api（契约 jar）│◀────增量扫描──│ search-service        │◀──/search──│ 基座首页  │
│  SearchSourceContributor│            │  扫描调度/闲时任务/MQ  │            │ 搜索卡片  │
│  RemoteSearchDataProvider│──索引事件─▶│  Elasticsearch 9.x    │            │ 顶栏搜索  │
│  TenantEventSender(MQ) │            │  三层权限过滤          │            └─────────┘
└──────────────────────┘             └──────────────────────┘
```

- **引擎解耦**：索引/检索只依赖 `SearchEngineClient` 端口（默认 `EsSearchEngineClient`），换引擎声明同接口 Bean 即整体替换。
- **业务解耦**：业务工程只依赖 `search-api` jar（照 mse-open 的 OpenApiContributor 模式），mse-search 零业务依赖；源登记经推送器启动推送 + 周期重推自愈；增量数据按 `providerGroup` 泛化寻址（dubbo GenericService / 单体反射）。
- **目录租户化**：`search_source`/`search_task`/`search_scan_cursor` 均为租户隔离表（BaseEntity），每租户一份目录、独立启停/调参/字段配置与扫描水位。业务自注册的源描述由 `SearchSourceFleetSeeder` 扇出播种至「平台 + 全部租户」归属上下文（逐上下文 `runAs`，路由拦截器切归属库；平台 0 单体双库形态落平台库、业务服务未注册 platform 数据源时回落主库平台行，与 mse-monitor 双端语义一致）——单上下文失败等下轮推送自愈，新租户建库后最迟一个推送周期（默认 5 分钟）目录自动就绪。ES 索引按「租户 × 源」物理分桶（`{index-prefix}-{sourceCode}-{tenantId}`），mapping/字段类型/新增字段完全租户自治，重建与清理互不影响。

## 二、增量通道

| 通道 | 说明 | 依赖 |
| --- | --- | --- |
| 定时扫描（基线） | 按「租户 × 源」调度：调度器遍历归属上下文（平台 + 全部租户，`runAs` 切归属库），按各租户目录的 cron 扫描增量水位（默认 update_time），bizId 散列分片并行拉取，拉取请求携带 `tenantId`（供数端显式按其过滤），游标按租户 × 源 × 分片独立记账（崩溃续扫），忙时限流闲时全速 | 无（无 MQ 环境也可用） |
| MQ 事件（实时） | 业务写操作后 `tenantEventSender.send(SearchIndexTopics.TOPIC, EVENT_DOC_UPSERT/DOC_DELETE, SearchIndexPayload)`；按事件租户的目录校验源——未登记抛错重投（等目录播种自愈）、已停用告警丢弃 | mq-starter + RocketMQ（未配 name-server 时监听器不装配，事件发送 noop 降级） |

> **删除必须走 MQ 事件**：MP 逻辑删除不更新 update_time，定时扫描覆盖不到（契约 javadoc 已注明）。
>
> **写入护栏**：扫描写入侧按当前扫描租户二次过滤文档（`doc.tenantId == 扫描租户`），旧版供数器（全租户拉取）混跑时多拉的异租户文档在此丢弃，升级窗口期不串写。

管理端「源管理 → 调参」的扫描 cron 支持图形化选择（秒/分/时/日/月/周/年七页签，日与周二选其一）与最近 10 次执行时间预览——`GET /search/source/cron-preview` 与调度器同用 Spring `CronExpression` 解析，预览语义与实际触发一致（表达式非法时内联提示，不弹全局错误）。扫描参数/启停均为租户级资产（各租户独立生效）。

## 三、三层权限过滤（查询侧强制注入，不信任前端）

1. **租户隔离**：`tenantId` 取自 TenantContext（缺失快速失败）；目录与索引双重隔离——检索只在会话租户自己的目录启用的源、自己的索引（`{prefix}-{source}-{tenantId}`）上进行，文档 `tenantId` 字段保留为防御过滤，文档 `_id = {tenantId}:{sourceCode}:{bizId}`。
2. **来源可见性**（权限码 + 可见角色，同时生效、任一不满足即整源不可见）：
   - `permissionCode` 业务契约字段（业务推送默认 + 管理端可调，重推时业务侧刷新）：不在当前用户权限集（Sa-Token 会话，超管 `*` 放行）时整源不参与检索；
   - `visibleRoles` 管理端调参资产（业务重推不覆盖）：源管理「调参」按多选角色下拉配置（角色候选拉 `/system/role/page`，平台端 `/system/role/platform/page`；空 = 不限制），用户持有任一配置角色（Sa-Token 角色集）才可见——与基座门户卡片可见角色同语义。
3. **个人数据范围**（与底座 `@DataPermission` 五档同语义，复用 `DataScopeResolverImpl` 合并算法）：
   - 文档字段：`ownerUserId`（对应 create_by/负责人）、`deptId`（对应 dept_id）、`visibleType`（`all`=公开文档全员可见）、`visibleUserIds`（显式共享用户）；
   - 源开关 `dataScopeEnabled`（默认开；公告类无归属源可关，关闭时文档一律按公开落索引）；
   - 范围解析装配链：单体形态直接复用本地 `DataScopeParamResolver`（service-system 在场）；微服务形态经 api-system 契约 `RemoteDataScopeService` RPC 解析（异常保守降级「仅本人」）；均可声明自定义 Bean 整体替换。

## 四、闲时处理

- `mse.search.idle-window`（默认 `00:00-07:00`，支持逗号分段与跨天如 `22:00-06:00`）；
- 任务中心三类任务（按归属租户创建与执行，作用域 = 本租户索引）：**全量重建**（删本租户索引重建 + 水位归零重扫，保证无孤儿文档）、**索引优化**（force-merge）、**立即扫描**（从当前水位增量同步，不等价于重建）；
- `idleOnly=1`（重建/优化默认）仅在闲时窗口内执行，工作时间触发则挂起等待；窗口内扫描批间不限流全速；挂起任务可在任务中心行内「立即执行」改为立即（`idleOnly` 翻转为 0，调度器下一轮拾起，复用 `search:task:save` 权限）；
- 多实例防重入：任务状态 CAS 抢占 + Redisson `LockOps` 分布式锁（扫描锁按租户 × 源隔离，任务锁按任务 id）。

管理端「索引管理」页面暴露会话租户的引擎真实索引视图（区别于热词统计页的按源水位，含源已删除后遗留的索引）：索引清单（健康/状态/文档量/存储大小）、数据浏览（关键词过滤 + 单条文档 JSON 详情）、清空（保留结构与 mapping，文档清零）与删除索引——与任务中心的全量重建互补。接口为 `GET /search/index/list`、`GET /search/index/docs`、`POST /search/index/{indexName}/clear`、`DELETE /search/index/{indexName}`；所有按索引名的操作均校验 `index-prefix-*` 前缀 + 会话租户后缀（`-{tenantId}`）白名单，禁止越权操作其他引擎或其他租户的索引。

## 五、业务接入三步（只动业务工程）

1. **引依赖**（业务 service 模块）：`vip.mseapp:search-api:1.0.0-SNAPSHOT`；
2. **声明源 + 数据提供者**：
   ```java
   // ① 源清单（业务自动装配类里 @Bean，按名判重）
   @Bean(name = "oaSearchSourceContributor")
   public SearchSourceContributor oaSearchSourceContributor() {
       return () -> List.of(descriptor("search:oa:task", "OA 任务", "/sub/oa/detail/task/{bizId}", "oa:task:list"));
   }
   // ② 增量数据提供者（@Component，providerGroup 返回本服务名）
   @Component
   public class OaSearchDataProvider implements RemoteSearchDataProvider {
       @Override public String providerGroup() { return applicationName; }
       @Override public String fetchBatch(String requestJson) {
           FetchBatchRequest req = parse(requestJson);
           // 扫描按「租户 × 源」执行：显式按 req.getTenantId() 过滤数据（不依赖上下文透传）；
           // 返回文档 tenantId 与之一致（写入侧按扫描租户二次过滤兜底）；
           // 水位 = update_time > watermark AND MOD(bizId, shardCount) = shardIndex；逻辑删除行 deleted=true 返回
           return toJson(result);
       }
   }
   ```
3. **（可选）实时通道**：微服务 boot 引 `mq-starter`，写操作后发索引事件（删除务必发）。

源可声明 `domain`（业务域显示名，如「OA域」「微信公众平台域」，可空）：搜索端把已注册源的去重域自动聚合为「全部域 + 各域」过滤选项——按域过滤时仅该域的源参与检索；不声明则仅参与「全部域」。`domain` 属描述字段，随源推送幂等更新，管理端源管理亦可调整。

业务私服仓库需先 `mvn install` mse-search（或发布到私服）。首个业务参考实现：**mse-oa**（`search:oa:instance` / `search:oa:form-data` 双源 + MQ 实时事件 + 降级说明，见其 README「搜索接入」一节）。

## 六、源字段配置（编码默认 → 业务字段目录 → 源字段配置 → 租户覆盖/新增）

检索字段不再只能写死在业务供数代码里，四级输入逐层递进：

1. **编码默认**：业务在 `SearchSourceDescriptor.fields` 里声明本源会随 extra 携带的字段及默认用法（`SearchFieldDescriptor`：field/label/type/searchable/weight/highlightable/returned），随源自注册推送落库（`search_source.fields_json`）；管理端未定制时随推送刷新，**一旦定制（`fields_customized=1`）推送不再覆盖**——与扫描参数同一"管理端资产"语义。
2. **业务字段目录（按需拉取，不落库）**：业务 Provider 可选实现 `RemoteSearchDataProvider#fieldCatalog`（请求 `{"sourceCode":"..."}`，返回 `SearchFieldCatalogResult` JSON），实时暴露本服务可匹配的字段元数据——如 OA 表单发布后的控件清单（field=控件 id、label=控件标题、type=控件类型映射），动态 schema 不再依赖"先有数据再采样"。目录由业务侧实时生成，mse-search 不落库：管理端「源管理 → 字段 → 从业务目录导入」与租户端「搜索配置 → 从业务目录添加」按需实时拉取勾选；同名字段同类型自动合并（跨表单/跨租户共搜），类型冲突整条排除进 `conflicts`；业务未实现契约时降级提示，既有功能零影响。首个接入方 mse-oa（oa-form `OaSearchFieldCatalog`）。
3. **源配置**：源管理「字段」维护（权限点 `search:source:field`），支持手工添加、「从数据采样」与「从业务目录导入」。字段配置驱动三件事：生成本租户索引的 ES mapping（目录租户化后每租户 × 每源一个索引，新增字段经 putMapping 增量生效）、索引写入时把 extra 中的声明字段**提升为顶层映射字段**（flattened extra 不支持加权 multi_match/高亮，必须落顶层）、查询组装 multi_match/高亮字段（上限 `mse.search.field.max-per-source` 默认 100）。每租户独立配置，推送刷新与本租户定制（`fields_customized=1` 不覆盖）语义不变。
4. **租户覆盖/新增**：租户端菜单「搜索配置」（`search_tenant_field` 表，租户隔离）——**覆盖**：只能引用源已声明字段，仅调整可搜/权重/高亮/展示/显示名，不能改类型；**新增**：可从业务字段目录添加未收录字段（携带目录类型进入本租户索引 mapping；保存时实时对照业务目录校验，目录不可用或字段不在目录则拒绝；单源上限 `mse.search.field.tenant-max-added` 默认 50）。**系统保留字段（title/content/tenantId/permissionCode/visibleUserIds 等基础与过滤字段）任何配置路径均不可占用**——过滤字段被重映射会破坏查询侧强制注入的租户隔离。

字段项结构与用法约定：

```json
[{ "field": "status", "label": "审批状态", "type": "keyword",
   "searchable": false, "weight": 1, "highlightable": false, "returned": true }]
```

- `type`：`keyword`（精确值）/ `text`（全文，用索引分析器）/ `number` / `date`；`searchable` 对 text 为**加权全文检索**、对 keyword 为全值精确匹配（number/date 不可检索）；`highlightable` 仅 text 有效；
- 未配置任何字段时行为与历史一致（内置 `title^3 + content` 检索，配置字段是**追加**而非替换）；
- 字段集合或类型变更后：**新文档立即可搜，存量文档需建 FULL_REBUILD 任务全量重建**（源管理保存时会提示一键创建）；字段值缺失则跳过提升，稀疏无害；租户新增字段取消后 mapping 中的字段无法删除（残留稀疏无害）；
- 搜索端经 `GET /search/api/display-fields` 取各源展示字段（key+label，按租户覆盖合并后口径），据此渲染结果 extra 值。

## 七、基座侧消费（零开发）

- **首页搜索卡片**：首页「添加卡片」→「全局搜索」（管理员也可在设计器把卡片摆进默认模板）；
- **顶栏全局搜索**：默认开启，管理员可在「首页配置 → 布局设计」用「顶栏全局搜索」开关关闭；
- 搜索结果点击经 `jumpUrl`（服务端按源模板渲染，支持 `{bizId}`/`{extra.字段}` 占位）跳转对应子应用页面；
- **业务域过滤**：输入框下方渲染「全部域 + 各域」chips——选项经 `GET /search/api/domains` 取已注册源 `domain` 去重（权限口径与查询一致，无域时整行隐藏）；选中域随 `POST /search/api/query` 的 `domain` 参数提交，切域后自动重搜。

## 八、本地运行

```bash
# 1. 中间件（含 Elasticsearch 9.2.8 + smartcn；客户端 jar 为 BOM 仲裁的 9.2.9，同 minor 兼容）
cd mse-framework/docker && docker compose up -d elasticsearch
# 2. 基座与依赖
mvn install -DskipTests                      # 根目录（框架 + api-system 新契约）
# 3. 初始化 SQL：免手工执行——业务表脚本（mse-target: master）随服务启动自动应用（mse.tenant.schema.auto-migrate=true，失败阻断启动）；
#    基座接入脚本（mse-target: base）由 starter-bridge 就绪即推 mse-system 按账本应用（周期重推自愈），重复推送无害。
# 4. Nacos 配置（服务专属 Data ID）
CONFIG_DIR=mse-modules/mse-search/docker/nacos/config sh mse-framework/docker/nacos/init-configs.sh
# 5. 启动（boot 端口 8096，网关 /search/** → lb://mse-search）
mvn spring-boot:run -f mse-modules/mse-search/search-boot/pom.xml
# 6. 管理端：源管理/任务中心/热词统计/搜索测试/索引管理均为租户端页面（每租户一份目录与索引），
#    平台域（tenant 0）目录经自注册推送扇出播种；搜索测试页可手工推文档 + 对比「忽略个人数据过滤」验证三层过滤
```

前端：`search-ui`（dev 4000，构建 `/sub-apps/search/`）；无业务接入时的验收路径 = 手工登记源 → 搜索测试推文档 → 查询高亮。

**单体聚合**：search-service 已聚合进 `mse-sample/mse-monolith`（pom 引入即装配，`mse.rpc.mode=local` 下
源登记/增量数据/数据范围全部本地直调）；单体侧只需运行 Elasticsearch（SQL 免手工执行：
`mse.tenant.schema.auto-migrate` 已开启随启动自动建表，基座注册由 mse-target: base 版本化脚本经 starter-bridge 推送），
配置与启动说明见单体 README「启用 Elasticsearch」一节。

## 九、资源占用（登记于 alloc-tables.md）

boot 8096 / ui 4000 / micro_app id=9 / 菜单 480-484、486-487 / 权限 4201-4211 / 网关 `/search`（需登录，无白名单）。

## 十、目录租户化（终态说明）

源目录/扫描/任务/索引自本版本起按租户归属（背景：源管理等页面已转租户端拥有，全局目录 + 租户路由会导致独立库租户目录永远为空）：

- **数据模型**：`search_source`/`search_task`/`search_scan_cursor` 切换 BaseEntity（租户隔离），`V20261001_02_search__search_init.sql` 补 `tenant_id` 列并重建租户维度唯一键（`-- mse-target: master`，主库与懒初始化租户库自动应用；**存量独立库部署后执行一次管理端「批量同步」补齐**）；`V20261001_03_search__search_platform.sql`（`-- mse-target: base` + `-- mse-base-scope: platform`）在平台库按终态建表（单体双库形态平台会话落平台库所需，范式同 monitor）。
- **存量行归属**：升级后主库旧目录行 `tenant_id=0` = 平台自有目录；各租户目录由扇出播种按描述符默认重建（租户级调参历史不迁移），租户游标为空 → 首扫从水位 "0" 全量重建租户索引，无需数据迁移。
- **索引分桶**：每租户 × 每源一个索引 `{index-prefix}-{sourceCode}-{tenantId}`；旧共享索引 `mse-search-*` 成孤儿数据，可选手工清理。mapping/字段类型/新增字段完全租户自治。
- **契约变更**：`FetchBatchRequest` 新增 `tenantId`（JSON 序列化双向兼容）；供数端必须按其过滤数据（mse-oa 已同步）。微服务形态 search-boot 与业务 boot 需同批升级——混跑期写入侧按扫描租户二次过滤兜底，不会串写，但旧供数器只能供出平台域数据。

## 十一、信创

数据源方言 profile 仍可经 `--spring.profiles.active=dm|kingbase` 切换（mse-micro-db.yaml），但业务表版本化脚本当前随 jar 仅提供 MySQL 方言；Elasticsearch 为独立中间件，不受业务库方言影响。
