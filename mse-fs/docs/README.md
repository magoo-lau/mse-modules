# mse-fs —— 文件存储服务（产品文档）

> **状态：已落地**（一期：双实现存储抽象 + 文件管理 + 公开素材 + RPC 契约 + 开放 API + 存储迁移，工程创建于 2026-09-18）。
> 本文档集为产品文档留档，实际落库以工程内版本化脚本为准（`fs-service/src/main/resources/db/mysql/`）；02 中的 id 即种子脚本原文转录。

## 一、产品定位

面向**多租户 SaaS** 的**统一文件存储基础设施**：为基座与全部业务积木提供「一套契约、多种后端」的文件上传 / 下载 / 管理 / 分发能力——本地文件系统缺省兜底，RustFS 1.x（S3 兼容，同样适用于 MinIO 等）对象存储按配置切换，业务与运维零感知。

在本仓库中的形态：**mse-modules 业务工程**（独立 Maven 工程，不进根 pom 聚合），wujie 微前端子应用 + 微服务启动器，积木拼法与 mse-demo 一致。与常规业务工程的一点差异：**本工程无 `fs-api` 模块**——对外 RPC 契约放在框架区 `mse-framework/mse-api/api-fs`（`RemoteFileService` + DTO + `FsAssetUrls` 工具），本工程是实现方：

- `fs-service`：业务积木（SPI 自装配，Controller / Service / Mapper / 存储实现随 jar 就绪）
- `fs-boot`：微服务启动器（micro-starter + fs-service + S3 SDK，纯 `@SpringBootApplication`）
- `fs-ui`：子应用前端（Vue3 + Element Plus 紧凑型，wujie 嵌入基座，文件管理页）
- `docker/`：服务专属 Nacos 配置留档 + RustFS 单机部署栈（可选）

**多租户**：双层隔离——元数据 `fs_file` 行级隔离（租户插件自动过滤）+ 物理存储键首段恒为租户 ID（`{tenantId}/...`）；平台世界（tenant_id=0）经平台库自持副本独立存放平台侧素材。

**三类使用者**：最终用户（文件管理页的上传 / 下载 / 管理）、业务开发者（按接入标准把模块存储接到 mse-fs）、实施运维（存储后端部署 / 切换 / 迁移）。

## 二、文档导读

| 分册 | 内容 | 读者 |
| --- | --- | --- |
| [01-功能规划.md](01-功能规划.md) | 能力模块与功能点清单（存储抽象 / 文件管理 / 公开素材 / 迁移 / 契约 / 开放 API）、业务文件存储接入标准附则 | 产品 / 后端 / 全员 |
| [02-权限规划.md](02-权限规划.md) | 子应用注册、页面菜单（sys_menu）实际清单、权限点（sys_permission）全量清单与编码规范 | 后端 / 前端 |
| [03-角色规划.md](03-角色规划.md) | 建议角色（工程未种角色，注明）、角色 × 能力授权矩阵、双层隔离数据权限、种子落库情况 | 后端 / 实施 |
| [04-操作说明书/](04-操作说明书/README.md) | **分角色操作说明书**（总览 + 2 册：平台管理员 / 租户用户），含通用操作与常见问题 | 最终用户 / 培训交付 |
| [05-配置手册-零基础版.md](05-配置手册-零基础版.md) | **面向门外汉的部署配置指南**：名词卡、容器一键部署、本地 / RustFS 两种模式、租户初始化、排查表与上线清单 | 实施 / 现场 IT |

阅读顺序：01 → 02 → 03。02 的表格即 `V20261001_01_fs__fs_base.sql` 种子的原文转录。**04 为面向最终用户的交付文档、05 为面向实施的零基础部署文档**，均以系统实际界面与配置为准。

## 三、资源分配（实际占用，已登记 alloc-tables.md）

| 资源 | 实际值 | 说明 |
| --- | --- | --- |
| 后端端口 `server.port` | **8095** | 微服务形态 fs-boot（Nacos Data ID `mse-fs.yaml` 留档）；单体聚合不占独立端口 |
| 前端 dev 端口 | **3900** | fs-ui vite dev server；构建 base `/sub-apps/fs/` |
| 子应用注册 `sys_micro_app.id` | **8** | name=`fs`，side=1（租户端），标题「文件存储」，sort=8 |
| 页面菜单 `sys_menu.id` | **470-471** | 470=type=1 分组目录「文件存储」；471=type=3 页面「文件管理」（`/file`） |
| 权限点 `sys_permission.id` | **4101-4105** | 41xx 段 fs 独占（基点 4100）：4101-4104 文件查询/上传/下载/删除 + 4105 存储迁移 |
| 权限码前缀 | `fs:` | 格式 `fs:<域>:<动作>`，与 `mes:` / `oa:` 等工程同风格 |
| 网关路由前缀 | `/fs` | `Path=/fs/** → lb://mse-fs`（mse-gateway.yaml 已登记） |
| 网关免登录白名单 | `/fs/asset/**` | 公开素材通道（仅放行 access_level=public 的文件） |
| RustFS 端口 | **9000 / 9001** | S3 兼容 API / 管理控制台（`docker/rustfs/compose.yaml`，可选部署） |
| 微服务应用名 | `mse-fs` | spring.application.name；`@TenantServiceModule("mse-fs")` |
| 开放 API 目录码 | `open:fs:file-*` | mse-open 目录自注册（四条），非 sys_permission 权限点 |

## 四、工程结构蓝图（实际）

```
mse-fs/
├── pom.xml                  # 独立父 pom（parent=mse-starter-parent，relativePath 置空）
├── README.md                # 工程主文档（功能权威描述 + 接入标准）
├── docs/                    # 本文档集（产品文档）
├── fs-service/              # 业务积木（纯 jar，SPI 自装配；契约在框架区 api-fs，本模块为实现方）
│   ├── storage/             # FileStorage 抽象 + LocalFileStorage + RustFsFileStorage（AWS SDK v2）
│   ├── config/              # FsStorageProperties（mse.fs.storage.*）
│   ├── entity/ mapper/      # FsFile（BaseEntity 租户基类）+ Mapper（无 XML）
│   ├── service/ controller/ # FsFileService + 文件/公开素材/存储迁移三个 Controller
│   ├── dto/ openapi/ rpc/   # 分片直传 DTO + FsOpenApiContributor + RemoteFileServiceImpl
│   ├── autoconfigure/       # FsService/FsStorage/FsController 三个自动装配 + imports 登记
│   └── src/main/resources/db/mysql/  # V20261001_01（base 基座接入）/ 02（master 业务表）/ 03（platform 平台库副本）
├── fs-boot/                 # 启动器（vip.mseapp.boot.fs.FsBootApplication，micro-starter + fs-service + s3 SDK）
├── fs-ui/                   # 子应用（dev 3900；base=/sub-apps/fs/；文件管理页）
└── docker/
    ├── nacos/config/mse-fs.yaml   # 服务专属配置留档（端口/存储/预签名，环境变量可覆盖）
    └── rustfs/compose.yaml        # RustFS 1.x 单机部署（可选，不部署即本地模式）
```

部署形态：微服务为默认形态（Nacos + Dubbo，网关 `/fs` 前缀）；单体聚合仅需引入 `fs-service` jar（`mse.rpc.mode=local` 本地直调；不引 S3 SDK 时 classpath 无对象存储客户端，自动纯本地模式）——`mse-sample/mse-monolith` 已按此引入。

## 五、平台协作约定（红线）

1. **不改框架区**：RPC 契约 `api-fs` 与 `FsAssetUrls` 属框架区契约积木；本工程为实现方，定制经 `@ConditionalOnMissingBean` 替换语义（存储实现、RPC 实现均可整体替换）。
2. **SQL 免手工执行**：业务表脚本（`-- mse-target: master`）随启动自动迁移；基座接入脚本（`-- mse-target: base`）由 starter-bridge 推送 mse-system；平台库副本脚本（`mse-base-scope: platform`）随基座应用启动直建。
3. **权限点一律挂靠本工程 type=3 页面菜单（471）**，参与角色授权分组与租户套餐裁剪；前端按钮用 `v-permission` 按权限码控制。
4. **业务接入零自造落盘**：业务模块需要文件存储时一律按 [01-功能规划](01-功能规划.md) 第九节接入标准（引 api-fs + 双嵌套互斥条件装配，mse-fs 在则统一存储、不在则自动降级本地）。
5. **资源已登记**：第三节实际值均已登记 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md`。
