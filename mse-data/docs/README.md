# mse-data —— 多租户数据中台（产品规划）

> **状态：已落地**（数据源管理、数据集成同步、数据地图/血缘、数据服务 API、数据质量、AI 智能查询均已交付；三期 OLAP 见「能力演进」）。
> 本文档集为产品规划留档，实际落库以工程内版本化脚本为准（`data-service/src/main/resources/db/mysql/`）；02 的权限清单即种子脚本 `V20261001_01_data__data_base.sql` 的逐条转录。

## 一、产品定位

面向**多租户 SaaS** 的数据中台：把企业系统脚手架（ai-mse）的用户/租户/权限底座延伸为数据中台，覆盖「**数据源接入 → 集成同步（ODS 贴源层）→ 元数据/血缘 → 数据服务 API → 数据质量**」的数据链路闭环。每个租户接入自己的数据源、发布自己的数据 API，行级隔离与 RBAC 全部继承基座。

在本仓库中的形态：**mse-modules 业务工程**（独立 Maven 工程，不进根 pom 聚合），wujie 微前端子应用 + 微服务启动器，积木拼法与 mse-demo / mse-oa 一致：

- `data-api`：跨服务 RPC 契约（`RemoteDataApiService.invoke`，经 mse-open 自注册对外开放）
- `data-service`：业务积木（SPI 自装配，Controller / Service / Mapper / 同步引擎随 jar 就绪）
- `data-boot`：微服务启动器（micro-starter + data-service，纯 `@SpringBootApplication`，端口 8093）
- `data-ui`：子应用前端（Vue3 + Element Plus 紧凑型，wujie 嵌入基座，七页面）

**多租户**：`dat_*` 十四张表 `tenant_id` 行级隔离为基线（可按租户独立库路由）；同步生成的 ODS 贴源表（`ods_*`）由引擎动态建表并自带 `tenant_id` 列；中台库通道执行期自动注入租户谓词——物理共享表、逻辑租户隔离。

## 二、文档导读

| 分册 | 内容 | 读者 |
| --- | --- | --- |
| [01-功能规划.md](01-功能规划.md) | 8 大功能模块与功能点清单、核心业务闭环、开放 API（/openapi/data）专节 | 产品 / 全员 |
| [02-权限规划.md](02-权限规划.md) | 子应用注册、页面菜单（sys_menu）、权限点（sys_permission）全量清单与编码规范 | 后端 / 前端 |
| [03-角色规划.md](03-角色规划.md) | 建议角色清单、角色 × 模块授权矩阵、数据权限设计、种子落库情况 | 后端 / 实施 |
| [04-操作说明书/](04-操作说明书/README.md) | **分角色操作说明书**（总览 + 3 个岗位手册：数据管理员 / 数据分析师 / 数据服务开发者），含通用操作、状态字典与常见问题 | 最终用户 / 培训交付 |
| [05-配置手册-零基础版.md](05-配置手册-零基础版.md) | **面向门外汉的部署配置指南**：名词卡、容器一键部署（单体）、Nacos 配置要点、租户/套餐/角色初始化、外部数据源连通性、排查表与上线清单 | 实施 / 现场 IT |

阅读顺序：01 → 02 → 03。02 的表格与种子脚本逐条对应。**04 为面向最终用户的交付文档、05 为面向实施的零基础部署文档**，均以系统实际界面与配置为准。

## 三、资源分配（实际占用值）

> 以下均为**已占用实际值**，登记见 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md`。

| 资源 | 实际值 | 说明 |
| --- | --- | --- |
| 后端端口 `server.port` | **8093** | 微服务形态 data-boot（Nacos 留档 Data ID `mse-data.yaml`）；单体聚合不占独立端口 |
| 前端 dev 端口 | **3700** | data-ui vite dev server |
| 子应用注册 `sys_micro_app.id` | **6** | name=`data`，side=1（租户端），标题「数据中台」 |
| 页面菜单 `sys_menu.id` | **440-447** | 440=type=1 分组目录；441-447 七个页面（概览/数据源/集成/地图/服务/质量/白名单） |
| 权限点 `sys_permission.id` | **9501-9523** | 23 个权限点，全部挂靠页面菜单（与种子脚本逐条一致） |
| 权限码前缀 | `data:` | 格式 `data:<模块>:<动作>`，与 oa/mp/mes 等工程同风格 |
| 网关路由前缀 | **`/data`、`/openapi/data`** | `Path=/data/** → lb://mse-data`；`/openapi/data/**` 在网关白名单（签名调用免登录） |
| 微服务应用名 | `mse-data` | spring.application.name；`@TenantServiceModule("mse-data")` |
| sys_config 参数种子 | 无 | 开关类配置走 Nacos（`mse.data.*`），未使用 sys_config 参数 |

## 四、能力演进

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| 已交付 | 数据源管理（4 类库 + AES-GCM 口令加密）、数据集成（整表/自定义查询、全量/增量、cron、依赖编排）、任务级血缘、数据地图（元数据/采样/导出）、数据服务（审批流 + HMAC 签名 + 授权/限流）、数据质量（6 类规则）、AI 智能查询（`data_query` 反向工具）、查询白名单 | 已上线 |
| 三期（规划） | DataWriter 扩展 ClickHouse/Doris 建 DWS 汇总层，数据服务可指定查询存储；实时接入（binlog/CDC）；指标/标签层 | 未落地（规划） |
| 候选（规划） | 质量波动规则多基线对比、血缘字段级展开、API 配额与计费、导出模板化 | 未落地（规划） |

## 五、工程结构蓝图（实际）

```
mse-data/
├── pom.xml            # 父 pom：import mse-dependencies BOM（自补 postgresql 驱动版本仲裁）
├── docs/              # 本文档集（产品系统规划）
├── data-api/          # RPC 契约（RemoteDataApiService + DTO；经 mse-open 自注册对外开放）
├── data-service/      # 业务积木（SPI 自装配；vip.mseapp.data）
│   ├── config/        # DataProperties（mse.data.*）、DataCrypt（AES-GCM）、DataEngineConfig
│   ├── controller/    # /data/source /data/allow /data/sync /data/meta /data/api /data/apiapp /data/quality /data/overview
│   ├── engine/        # 同步引擎 DataReader/DataWriter SPI + JdbcReader/JdbcWriter + SyncEngine/SyncScheduler/QualityScheduler
│   ├── open/          # 开放 API：ApiQueryExecutor / OpenSignatureVerifier / OpenApiService / OpenApiController
│   └── support/       # DataConnectorManager（外部源连接池）、ApiSqlGuard（jsqlparser 防护）、MasterDbGuard（主库防呆）、TenantColumnProbe
│   └── src/main/resources/db/mysql/
│       ├── V20261001_01_data__data_base.sql   # 基座接入（mse-target: base，starter-bridge 自动推送）
│       └── V20261001_02_data__data_init.sql   # 14 张业务表（mse-target: master，启动自动迁移）
├── data-boot/         # 启动器：micro-starter + data-service（vip.mseapp.boot.data，8093）
├── data-ui/           # 子应用（dev 3700；构建 --base=/sub-apps/data/；七页面）
└── docker/nacos/config/mse-data.yaml   # Nacos 服务专属配置留档
```

部署形态：微服务为默认形态（Nacos + Dubbo，网关 `/data` 前缀）；单体聚合仅需 mse-monolith 引入 `data-service` jar（`mse.rpc.mode=local` 本地直调，mse-open 调用同样零改动）。

## 六、平台协作约定（落地红线）

1. **不改框架区**：一切定制经 SPI 自动装配 + `@ConditionalOnMissingBean` 替换语义实现。
2. **SQL 免手工执行**：业务表脚本（`-- mse-target: master`）随启动自动迁移（失败阻断启动）；基座接入脚本（`-- mse-target: base`）由 starter-bridge 自动推送 mse-system（幂等版本化脚本，命名 `V{yyyyMMdd}_{NN}_data__<描述>.sql`）。
3. **权限点一律挂靠本工程 type=3 页面菜单**，参与角色授权分组与租户套餐裁剪；前端按钮用 `v-permission` 按权限码控制。
4. **资源用后登记**：端口/号段占用登记在 `alloc-tables.md`（本工程三项均已登记：9501+、440-447、micro_app id=6）。
5. **数据服务双通道并存**：自持签名通道 `/openapi/data/**`（appKey + HMAC）与 mse-open 统一窗口 `POST /open/api/data/api/invoke`；新接入建议统一走 mse-open 窗口。
