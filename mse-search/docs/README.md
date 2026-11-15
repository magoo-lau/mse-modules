# mse-search —— 企业级全文搜索引擎服务（产品文档集）

> **状态：已落地**（目录租户化终态见 [01-功能规划](01-功能规划.md) 第十二节）。
> 本文档集为产品留档，功能与权限以工程实际实现为准（`search-service` 代码与 `db/mysql/` 版本化脚本）；02 中的全部 id 为种子脚本实际落库值。

## 一、产品定位

面向**多租户 SaaS** 的企业级全文搜索引擎（Elasticsearch 9.x）：把散落在各业务工程里的数据聚合为一个**统一搜索入口**——基座顶栏搜索框与首页「全局搜索」卡片一处输入、跨应用检索、点击直达业务详情页。

在本仓库中的形态：**mse-modules 业务工程**（独立 Maven 构建，不进根 pom 聚合），wujie 微前端子应用 + 微服务启动器，积木拼法与 mse-demo / mse-oa 一致：

- `search-api`：跨服务 RPC 契约（`SearchSourceContributor` 源自注册 / `RemoteSearchDataProvider` 增量供数 / 索引事件）——业务工程只依赖这个 jar 即完成接入
- `search-service`：业务积木（SPI 自装配，扫描调度 / 三层权限过滤 / 索引管理随 jar 就绪）
- `search-boot`：微服务启动器（micro-starter + mq-starter + search-service，纯 `@SpringBootApplication`）
- `search-ui`：子应用前端（Vue3 + Element Plus 紧凑型，wujie 嵌入基座）

**四大设计支柱**：

1. **业务解耦**：业务工程声明 `SearchSourceContributor` 即接入（照 mse-open 的 OpenApiContributor 模式），mse-search 零业务依赖；源登记启动推送 + 周期重推自愈，增量数据按 `providerGroup` 泛化寻址（dubbo GenericService / 单体反射）。
2. **租户自治**：源目录 / 扫描游标 / 任务 / 索引全租户归属——每租户一份目录、独立启停调参与字段配置，ES 索引按「租户 × 源」物理分桶（`{前缀}-{sourceCode}-{tenantId}`）。
3. **查询侧强制三层过滤**（不信任前端）：租户隔离 / 来源可见性（权限码 + 可见角色）/ 个人数据范围。
4. **双增量通道 + 闲时窗口**：定时增量扫描（bizId 散列分片、游标断点续扫）为基线，MQ 实时事件为加速；重任务（全量重建 / 索引优化）默认闲时窗口执行。

## 二、文档导读

| 分册 | 内容 | 读者 |
| --- | --- | --- |
| [01-功能规划.md](01-功能规划.md) | 六大管理页面 + 开放 API + 后台增量引擎的功能点清单，三层过滤与四级字段配置机制，业务接入三步 | 产品 / 全员 |
| [02-权限规划.md](02-权限规划.md) | 子应用注册、页面菜单（sys_menu）、权限点（sys_permission）全量清单（实录值）与编码规范 | 后端 / 前端 |
| [03-角色规划.md](03-角色规划.md) | 建议角色清单、角色 × 模块授权矩阵、数据权限（三层过滤）设计、种子落库情况 | 后端 / 实施 |
| [04-操作说明书/](04-操作说明书/README.md) | **分角色操作说明书**（总览含普通用户搜索使用 + 搜索引擎管理员 / 平台管理员两册） | 最终用户 / 培训交付 |
| [05-配置手册-零基础版.md](05-配置手册-零基础版.md) | **面向门外汉的部署配置指南**：ES 前置依赖、容器一键部署（含 ES 栈）、源接入与首扫、排查表与上线清单 | 实施 / 现场 IT |

阅读顺序：01 → 02 → 03。**04 为面向最终用户的交付文档、05 为面向实施的零基础部署文档**，均以系统实际界面与配置为准。

## 三、资源分配（实际占用值，已登记 alloc-tables.md）

| 资源 | 实际值 | 说明 |
| --- | --- | --- |
| 后端端口 `server.port` | **8096** | 微服务形态 search-boot；单体聚合不占独立端口 |
| 前端 dev 端口 | **4000** | search-ui vite dev server |
| 子应用注册 `sys_micro_app.id` | **9** | name=`search`，side=3（双端），标题「搜索引擎」 |
| 页面菜单 `sys_menu.id` | **480-484、486-487** | 480=type=1 分组目录（双端）；481-484 / 486 / 487 页面（租户端）；485 空号保留 |
| 权限点 `sys_permission.id` | **4201-4211** | 全部挂靠本工程页面菜单（见 02 分册） |
| 权限码前缀 | `search:` | 格式 `search:<模块>:<动作>` |
| 网关路由前缀 | `/search` | `Path=/search/** → lb://mse-search`（需登录，无白名单） |
| 微服务应用名 | `mse-search` | spring.application.name；`@TenantServiceModule("mse-search")` |
| Elasticsearch | **9.2.8 + smartcn** | `mse-framework/docker` 一键起（9200 端口）；客户端 jar 9.2.9 由基座 BOM 仲裁，同 minor 兼容 |

## 四、工程结构蓝图

```
mse-search/
├── pom.xml                  # 父 pom 指向 mse-starter-parent（relativePath 置空）
├── README.md                # 工程主文档（功能权威描述，含总体架构图）
├── docs/                    # 本文档集
├── search-api/              # 契约 jar：SearchSourceContributor / RemoteSearchDataProvider / 索引事件（业务接入唯一依赖）
├── search-service/          # 业务积木（SPI 自装配；engine 扫描与闲时调度 / scope 三层过滤 / tenant 目录播种 / mq 监听）
│   └── src/main/resources/db/mysql/   # V20261001_01 基座接入(base) / _02 业务表(master) / _03 平台库表(base+platform)
├── search-boot/             # 启动器：micro-starter + mq-starter + search-service（vip.mseapp.boot.search）
├── search-ui/               # 子应用（dev 4000；base=/sub-apps/search/；6 个页面）
└── docker/nacos/config/     # 服务专属 Data ID（mse-search.yaml）留档
```

部署形态：微服务为默认形态（Nacos + Dubbo，网关 `/search` 前缀）；单体聚合仅需 `mse-monolith` 引入 `search-service` jar（已聚合，`mse.rpc.mode=local` 下源登记/增量数据/数据范围全部本地直调）+ 运行 Elasticsearch。

## 五、平台协作约定（落地红线）

1. **不改框架区**：一切定制经 SPI 自动装配 + `@ConditionalOnMissingBean` 替换语义实现（引擎可换：声明 `SearchEngineClient` 同接口 Bean 即整体替换默认 `EsSearchEngineClient`）。
2. **SQL 免手工执行**：业务表脚本（`-- mse-target: master`）随启动自动迁移；基座注册（`-- mse-target: base`）由 starter-bridge 自动推送；平台库表副本（`mse-base-scope: platform`）按 monitor 同款范式就绪。
3. **权限点全部挂靠本工程 type=3 页面菜单**（租户端），参与角色授权分组与租户套餐裁剪；搜索开放 API（`/search/api/*`）不设权限码、登录即可，来源可见性由源级配置控制。
4. **业务接入只动业务工程**：引 `search-api` + 声明 Contributor/Provider，mse-search 不感知业务、业务不依赖 mse-search 运行（推送失败仅告警重试）。
5. **资源已登记**：第三节实际值均已在 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md` 登记（2026-09-18 起，2026-09-20 扩 486/487 与 4207-4211）。
