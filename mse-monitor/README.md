# mse-monitor 监控中心

独立业务工程（`mse-modules/mse-monitor`），提供轻量监控能力：**服务 / JVM / 慢SQL / 中间件 / 操作系统**的状态探测、指标采集、趋势展示与阈值告警。

- 监控中心为**平台/租户双端**子应用（平台端看平台自身 tenant_id=0 数据、租户端看本租户，按会话租户隔离；默认套餐不勾选，租户使用需在「套餐管理」勾选），网关前缀 `/monitor`，服务名 `mse-monitor`，端口 `8097`。
- **在线/离线模式（`mse.monitor.offline`，缺省 false）**：在线（缺省）= 监控由平台运营——平台端查看/维护，采集按目标归属分库（平台目标落平台库），租户端菜单虽双端存在但**人为控制不授权给租户**（默认套餐不含监控页面）；离线（true，租户离线私有部署形态）= 监控中心整体退化为「本地单一监控世界」——mon_* 采集/查询/告警/通知全部直连本地缺省库（不再路由平台库、不按会话租户过滤，本机 OS 目标同落本地），租户授权监控页面后在租户端维护本地监控。Nacos `mse-monitor.yaml` 与本地 yaml 均可配置，单体/微服务两形态通用（该标记仅在离线部署语义下使用，在线共享部署请保持 false）。
- 拉模式零侵入：对 Java 微服务只读 `/actuator/health`、`/actuator/metrics/*`、`/actuator/slowsql`（基座 micro/mono-starter 已内置 actuator，网关白名单已放行），被监控服务不需要任何代码改动（慢 SQL 需服务侧显式开启开关，见下）。目标服务开启 actuator 端点鉴权（mse.actuator.auth.token，starter-web 过滤器与网关 WebFilter 同规则）时，本服务需配置同源令牌 mse.monitor.actuator-token（经同一 MSE_ACTUATOR_TOKEN 环境变量对齐），探针拉取 /actuator/** 自动携带 X-Actuator-Token 头；令牌为空（默认）= 目标未开启鉴权，匿名拉取。

## 功能

| 监控对象 | 类型 | 方式 | 指标 |
| --- | --- | --- | --- |
| Java 微服务 | `service` | GET `{url}/actuator/health` | UP/DOWN、响应延迟 |
| 任意 URL（Nacos/ES 等） | `http` | GET 目标地址（JSON `status` 字段语义识别：green/yellow/red） | 存活、延迟、集群状态 |
| JVM | `jvm` | GET `{url}/actuator/metrics/*` | 堆已用/上限/使用率、非堆、线程数、GC 次数与耗时（每间隔增量）、进程 CPU |
| 慢 SQL | `slowsql` | GET `{url}/actuator/slowsql`（框架 starter-mybatis 内置端点） | 采集开关状态、累计/每间隔新增慢 SQL、最近慢 SQL 列表（时间/耗时/Mapper/SQL） |
| MySQL | `mysql` | JDBC 单连接 `SHOW GLOBAL STATUS/VARIABLES` | 存活、延迟、连接数、QPS（Questions 差值） |
| Redis | `redis` | Lettuce 直连 `INFO`（+抓取开启时 `SLOWLOG GET/LEN`） | 存活、延迟、内存用量、连接数、命中率、OPS、慢命令（key/耗时/参数，仅抓取期间） |
| RocketMQ | `rocketmq` | `DefaultMQAdminExt` 连 namesrv | namesrv 存活、broker/集群数 |
| 操作系统 | `os` | OSHI 本机采集（仅 monitor 所在主机） | CPU、内存、各挂载点磁盘、网卡速率、uptime |

- **采集**：调度器每 10s 轮询，启用中且到达采集间隔（默认 30s，可按目标配置）的目标提交线程池并发探测；采样落 `mon_record`，最近状态快照回写 `mon_target`；采样保留 7 天（可配），每日 04:30 清理。
- **告警**：按目标告警规则（JSON：`down/latencyMs/cpuPercent/memPercent/diskPercent/redisMemPercent/mysqlMaxThreads/jvmHeapPercent/slowSqlCount/redisSlowCmdCount`）评估；状态机 `firing → resolved`——触发即通知、持续触发不重复轰炸（可配周期重发）、恢复也通知。
- **通知（界面可配，独立直发）**：告警中心「通知设置」弹窗维护**渠道参数**（存 `mon_setting` 表）——
  - 站内消息收件人：从基座用户检索选择（`api-system` 契约 `RemoteMessageService` 直投）；
  - Webhook 群机器人：钉钉/企微/飞书 markdown 报文，地址即填即用（无需接收人），支持发送测试。
  监控中心**不经框架通知中枢**（v3.1 退出：通知设置即投递开关，无路由参与；框架已内置独立 webhook 渠道服务其他业务，与监控中心这份地址互不相干）。历史：v3 曾迁入中枢按 (monitor,*) 路由解析，2026-10-20 迁回独立直发。
- **页面**：监控总览（统计卡 + 目标状态网格 + 最近告警）、监控目标（CRUD + 启停 + 立即检测 + 差异化表单）、目标详情（当前指标 + echarts 趋势，近 1h/24h/7d）、告警中心（筛选 + 手动处理 + 通知设置）、缓存监控（浏览 + 删键，删键按权限显隐）。

## Redis 缓存监控

在监控目标（type=redis）之上提供缓存内容的查看与运维能力，Lettuce 按请求直连目标实例（凭据取自目标配置，与 RedisProbe 同源）：

- **缓存监控**（菜单 `/redis`，读权限 `monitor:redis:query`）：实例 INFO 概览（版本/内存/命中率/连接数/OPS/键量 + 各 db keyspace）、键树浏览（SCAN + 模式匹配取键名全集，前端按冒号分层构建 RDM 风格树——文件夹带子孙键计数、树内可再筛选，点叶子看值；类型与 TTL 按需查详情）、键值查看（string 自动识别 JSON 美化/二进制标记；hash/list/set/zset/stream 预览前 200 条，含编码与内存占用）。
- **删键**（同页，权限 `monitor:cache:delete` + 二次确认，删除数量返回提示）：删键按钮经 `v-permission` 按权限显隐——无删键权限的用户看到纯只读视图（原独立「缓存管理」页已并入本页，`V20261001_01_monitor__monitor_base.sql` 下线旧菜单 495 与旧权限点 4311）。
- **接口**（`/monitor/redis`，双端）：`GET /targets`、`GET /{targetId}/info|key-names|keys|key`（`monitor:redis:query`，兼容放行历史 `monitor:cache:query`；`key-names` 为键树数据源，分页 `keys` 平铺接口保留用于程序化访问）、`DELETE /{targetId}/key`（仅 `monitor:cache:delete`）。
- **保护边界**：键浏览单次 SCAN 收集上限 `mse.monitor.redis.scan-limit`（默认 5000），超出置 `capped` 标志提示缩小匹配模式，避免大库全量扫描；string 值预览截断 64K 字符、集合值预览 200 条；连接超时复用 `probe.timeout-ms`。

## Redis 慢命令抓取（排查工具，按需开启）

Redis 目标详情页提供「慢命令抓取」开关（存 `mon_setting` key=`redis-slowlog-<targetId>`，无 DDL），用于排查"哪些 key/命令慢"：

- **开启方式二选一**：持续时长（30 分钟/1 小时/2 小时，到期自动停）或指定起止时间窗（结束时间到自动停）；也可随时手动关闭，界面实时显示"抓取中"状态。
- **性能影响（界面常驻警示）**：抓取期间每轮采集会在原连接上多执行两条**只读**命令 `SLOWLOG GET`（条数上限 `mse.monitor.redis.slowlog-max-entries`，默认 10）/`SLOWLOG LEN`，非抓取时零额外开销；**监控只读，不修改服务端任何配置**。抓取范围取决于 Redis 自身 `slowlog-log-slower-than`（默认 10ms）与 `slowlog-max-len`（默认 128）配置；长时开启会常驻占用采集开销与服务端 slowlog 内存，排查完请及时关闭。
- **展示**：详情页「最近慢命令」表格（时间/耗时/命令/Key/参数），Key 取命令第一个参数（argv[1]），仅抓取期间产生数据，关闭即消失。
- **告警**：目标告警规则 `redisSlowCmdCount`（本轮抓到慢命令达到 N 条即告警）**仅在抓取开启期间评估**，关闭抓取即静默，避免忘记关开关误告警。
- **接口**（`/monitor/target`，双端）：`GET /{id}/slow-capture`（`monitor:target:query`）、`POST /{id}/slow-capture`（`monitor:target:save`，开启传 `{minutes}` 或 `{startAt,endAt}`，关闭传 `{"enabled":false}`）。


## 工程结构

```
mse-monitor/
├── monitor-service/     # 业务积木（纯 jar，SPI 自装配）
│   └── vip.mseapp.monitor
│       ├── autoconfigure/   # MonitorService/Controller AutoConfiguration（imports 登记）
│       ├── config/          # MonitorProperties(mse.monitor.*)、MonitorEngineConfig(@EnableScheduling + 采集线程池)
│       ├── controller/      # target/record/dashboard/alert/notify/redis 六个控制器（双端 @SaCheckOr）
│       ├── engine/          # MonitorScheduler（轮询调度）+ probe/（Probe SPI：service/http/jvm/slowsql/mysql/redis/rocketmq/os）
│       ├── entity+mapper/   # mon_target / mon_record / mon_alert / mon_setting
│       ├── service/         # Target/Collect/Alert(状态机)/Notify/NotifySetting/SlowCapture/Dashboard/RedisCache
│       └── support/         # AlertConfig/NotifySetting/SlowCaptureSetting(JSON 视图)、SimpleHttp、ActuatorAuth（actuator 鉴权头）
├── monitor-boot/        # 启动器：micro-starter + monitor-service，vip.mseapp.boot.monitor
├── monitor-ui/          # 子应用（Vue3 + Element Plus + echarts，wujie 接入，dev 4010）
├── monitor-service/src/main/resources/db/
│   │                   # jar 内版本化迁移脚本（MySQL）
│   ├── mysql/V20261001_02_monitor__monitor_init.sql（mse-target: master 业务库：4 张 mon_* 表 + 种子，启动自动应用）
│   ├── mysql/V20261001_01_monitor__monitor_base.sql（mse-target: base 基座库：注册+页面菜单+权限点，starter-bridge 自动推送）
│   ├── mysql/V20261001_03_monitor__monitor_platform.sql（mse-target: base 平台库：4 张 mon_* 表 + 平台种子，平台会话（tenant_id=0）语句经路由拦截器落平台库，单体双库/微服务基座库均需此副本）
│   └── mysql/V20261001_01_monitor__monitor_base.sql（mse-target: base 基座库：缓存两页合并——下线旧菜单 495/旧权限点 4311，删键权限 4312 改挂 494）
├── docker/nacos/config/mse-monitor.yaml   # 服务专属 Nacos 配置留档
└── README.md
```

## 快速开始

前置：基座 `mse-framework` 已 `mvn install`；MySQL/Redis 已就绪（单体 mse 库即可）。

1. **SQL**：免手工执行——业务表脚本（mse-target: master）随服务启动自动应用（`mse.tenant.schema.auto-migrate=true`，失败阻断启动）；基座接入脚本（mse-target: base）由 starter-bridge 就绪即推 mse-system 按账本应用（周期重推自愈）。
   base 脚本幂等登记子应用（sys_micro_app id=10）+ 页面菜单（490-494，合并脚本下线旧菜单 495）+ 权限点（4301-4310、4312，合并脚本下线 4311）；init 脚本建 4 张 `mon_*` 表并预置本平台监控目标全家桶（网关/基座/业务服务/Middleware，MySQL 与 Redis 默认启用，其余按部署实况启用）。
   分库形态：`V20261001_03_monitor__monitor_platform.sql`（mse-target: base）把同构 `mon_*` 表与平台种子（tenant_id=0）建进平台库——平台会话的全部 mon_* 语句落平台库，业务库里的平台行是分库前残留（调度器主库扫描在平台库已注册时排除该行集，避免采集回写跨库漂移）。
2. **Nacos 配置**（微服务形态；单体可跳过，参数走默认值）：
   ```bash
   CONFIG_DIR=mse-modules/mse-monitor/docker/nacos/config sh mse-framework/docker/nacos/init-configs.sh
   ```
3. **后端**：
   - 单体：`mse-sample/mse-monolith` 引入 `monitor-service` 依赖重新打包即可（SPI 自装配）；
   - 微服务：`mvn install -DskipTests` 后运行 `monitor-boot`（Nacos 注册，网关 `/monitor/** → lb://mse-monitor` 路由已在 `mse-gateway.yaml` 留档，运行期热更新或重新导入）。
4. **前端**：`monitor-ui` 下 `npm install && npm run dev`（独立调试）或 `npm run build` 部署到 `/sub-apps/monitor/`；子应用地址已随 db/base 版本化脚本推送登记，基座 `builtinMicroApps` 亦有兜底。
5. **权限**：平台管理员登录 → 平台端「监控中心」菜单；租户管理员登录 → 租户端「监控中心」（需套餐勾选；角色需勾选 monitor 权限点，超管默认全量）。

## 配置（mse.monitor.*）

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `scan-interval-ms` | 10000 | 调度轮询间隔（毫秒） |
| `default-collect-interval` | 30 | 新目标默认采集间隔（秒） |
| `retention-days` | 7 | 采样保留天数 |
| `executor-parallelism` | 4 | 采集线程池并行度 |
| `probe.timeout-ms` | 5000 | 单次探测超时（毫秒） |
| `redis.scan-limit` | 5000 | 缓存监控键浏览单次 SCAN 收集上限 |
| `redis.slowlog-max-entries` | 10 | 慢命令抓取单轮 SLOWLOG GET 条数上限 |
| `cleanup-cron` | `0 30 4 * * ?` | 采样清理 cron |
| actuator-token | 空 | 各目标服务 actuator 端点鉴权令牌（与服务侧 mse.actuator.auth.token 同源，经 ${MSE_ACTUATOR_TOKEN:} 环境变量对齐）；空 = 目标未开启鉴权，探针匿名拉取 |

告警收件人/Webhook **不在配置文件**：存 `mon_setting` 表，界面「告警中心 → 通知设置」维护。

## JVM / 慢SQL 监控启用前提

两类新目标依赖服务暴露对应 actuator 端点（本仓库各工程 Nacos 留档已统一 `management.endpoints.web.exposure.include: health,info,metrics,slowsql`，自建工程照此对齐）；目标服务开启 actuator 鉴权时还需本服务配好 actuator-token（见上表），否则拉取 401、目标显示 down。db/mysql 版本化脚本（`V20261001_02_monitor__monitor_init.sql`）预置的 JVM/慢SQL 种子目标**默认停用**，确认前提满足后再启用：

1. **JVM 目标**：服务 `exposure` 含 `metrics`（仅配置，无需改动代码）。指标经 `/actuator/metrics/*` 拉取，GC 为进程启动累计值差换算的每间隔增量，monitor 重启后首轮无增量属正常。
2. **慢SQL 目标**：除 `exposure` 含 `slowsql` 外，还需**目标服务显式开启**框架慢 SQL 开关（默认关闭、零开销，开启后每条语句一次计时、超阈值才进内存环形缓存）：
   ```yaml
   mse:
     mybatis:
       slow-sql:
         enabled: true        # 默认 false
         threshold-ms: 3000   # 慢 SQL 阈值（毫秒）
         capacity: 32         # 内存环形缓存条数
   ```
   开关未开启时目标显示 up 且「慢SQL采集=否」，不会误报 0 条慢 SQL。网关（WebFlux 无数据源）不支持慢SQL 目标。

## 实现说明与边界

- **内存态调度**：与 mse-data 同款 Spring `@Scheduled` 轮询；多实例部署 monitor-boot 会重复采集（数据无害但不必要），生产多实例需外置调度或单实例部署。
- **未就绪库安静跳过**：独立库租户自注册进路由表到初始化直建之间存在两级开放窗口（调度器 push+runIgnore 直连扫描不经过 `TenantDsLazyInitializer`；数据源注册可见早于物理建库、建库又早于懒初始化直建 `mon_*` 表），窗口期内目标/记录扫描缺库或缺表均属预期中间态——按 SQLState/消息识别后 DEBUG 一行跳过，初始化完成后自动纳入；其余异常（连不上、权限等）保持 WARN 带栈。新建独立库租户初始化期间出现 `Unknown database 'mse_tenantX'` 或 `Table 'mse_tenantX.mon_target' doesn't exist` 的 DEBUG 日志属正常，无需处理。
- **OS 监控范围**：OSHI 仅采集 monitor 所在主机；远程主机/SSH 采集/目标机 Agent 为后续可选扩展。
- **RocketMQ 探测**：`rocketmq-tools`（5.3.1，与服务端对齐）连 namesrv 拉集群信息；外层硬超时兜底，探测失败不影响服务本身。
- **MySQL QPS**：进程内缓存上次 Questions 计数做差值，服务重启后首轮无 QPS 值。
- **JVM/慢SQL 同款差值口径**：GC 次数/耗时、慢SQL 新增均为相邻两次采样差值，目标服务或 monitor 重启后首轮无增量值；慢SQL 明细仅存最近 5 条（SQL 截断 300 字符，只含静态文本与 `?` 占位符，无参数值），全量环形缓存在目标服务内存中（`/actuator/slowsql` 可直接查看）。
- **密码脱敏**：目标凭据查询统一返回 `******`，编辑时原值不变；Redis/MySQL 凭据仅用于探测器直连。

## 号段登记（alloc-tables.md 已同步）

| 资源 | 值 |
| --- | --- |
| 后端端口 | 8097 |
| 前端 dev 端口 | 4010 |
| sys_micro_app id | 10（side=3 双端） |
| 页面菜单 | 490（目录）+ 491-495 |
| 权限点 | 4301-4312（基点 4300） |
| 网关前缀 | /monitor |
