# mse-open —— 开放平台（能力开放层，产品规划）

> **状态：一期 + 二期已落地**（REST 开放 API + 应用/凭证/授权/限流/审计 + 管理端 + 业务积木自注册与泛化分发；二期请求签名验签 + IP/域名白名单；SSO 授权码模式为规划项）。
> 本文档集为产品规划留档，实际落库以工程内版本化脚本为准（`open-service/src/main/resources/db/mysql/`）；02/03 中的 id 均为脚本种子直接转录。
> 业务接入与第三方调用的**操作级指南**见 [open-api-guide.md](open-api-guide.md)（已有文档，独立维护）。

## 一、产品定位

开放平台是 ai-mse 基座的**统一对外窗口**：把基座的用户/部门/角色/租户能力，以**应用凭证（appKey/appSecret）+ access_token** 的方式安全开放给其他系统调用；同时提供统一身份绑定、用户扩展属性与全量用户纳管。业务积木（oa/mp/data/ai/fs…）经**自注册**把自有能力挂到统一目录，mse-open 负责**鉴权/授权/限流/审计/泛化分发**——业务新增开放能力 mse-open 零改动。

在本仓库中的形态：**mse-modules 业务工程**（独立 Maven 工程，不进根 pom 聚合），微服务启动器 + 平台/租户管理端子应用，积木拼法与 mse-demo 一致：

- `open-api`：注册契约（纯 jar）——业务积木自注册入口（OpenApiContributor + RemoteOpenApiRegistry + 推送装配）
- `open-service`：开放平台业务积木（SPI 自装配）——应用/凭证/token/鉴权链/目录接收/泛化分发/开放 API/管理端
- `open-boot`：微服务启动器（micro-starter + open-service，端口 8092，纯 `@SpringBootApplication`）
- `open-ui`：管理端子应用（Vue3 + Element Plus 紧凑型，wujie 嵌入基座，dev 3600）

**多租户**：接入应用必绑一个租户（0=平台域），应用调用开放 API 时全部数据访问被租户插件约束在该租户域内——凭证即数据边界。管理端（/openapp/**）走基座 Sa-Token 人机会话 + `open:*` 权限点，与开放 API 的机器凭证体系完全隔离。

## 二、文档导读

| 分册 | 内容 | 读者 |
| --- | --- | --- |
| [01-功能规划.md](01-功能规划.md) | 7 大功能模块与功能点清单、核心链路（凭证→token→调用→审计）、业务自注册接入专节 | 产品 / 全员 |
| [02-权限规划.md](02-权限规划.md) | 子应用注册、页面菜单（sys_menu）实际清单、权限点（sys_permission）全量清单与编码规范 | 后端 / 前端 |
| [03-角色规划.md](03-角色规划.md) | 建议角色清单（无角色种子）、角色 × 模块授权矩阵、数据权限设计、种子落库情况 | 后端 / 实施 |
| [open-api-guide.md](open-api-guide.md) | **业务接入与第三方调用详细指南**（六步接入 + curl 全示例 + FAQ），机制展开到可照抄 | 业务开发 / 接入方 |
| [04-操作说明书/](04-操作说明书/README.md) | **分角色操作说明书**（总览 + 2 册：开放平台管理员 / 接入方开发者），含状态字典与常见问题 | 最终用户 / 交付 |
| [05-配置手册-零基础版.md](05-配置手册-零基础版.md) | **面向门外汉的部署配置指南**：前置依赖、Nacos 配置、token/限流参数、租户初始化、排查表与上线清单 | 实施 / 现场 IT |

阅读顺序：01 → 02 → 03；动手接入看 open-api-guide.md；部署看 05。04 为面向最终用户的交付文档，以系统实际界面为准。

## 三、资源分配（实际占用，登记于 alloc-tables.md）

| 资源 | 实际值 | 说明 |
| --- | --- | --- |
| 后端端口 `server.port` | **8092** | 微服务形态 open-boot；单体聚合不占独立端口 |
| 前端 dev 端口 | **3600** | open-ui vite dev server；生产 `/sub-apps/open/` |
| 子应用注册 `sys_micro_app.id` | **5** | name=`open`，side=1（租户端，租户自管开放应用），标题「开放能力」 |
| 页面菜单 `sys_menu.id` | **450-455** | 450=type=1 分组目录；451 应用管理 / 452 调用日志 / 453 身份绑定 / 454 接入指南 / 455 API 目录 |
| 权限点 `sys_permission.id` | **7001-7012** | 7xxx 段（open 专用）；7001-7009 挂 451，7010 挂 452，7011 挂 453，7012 挂 455 |
| 权限码前缀 | `open:` | 格式 `open:<模块>:<动作>`；开放 API 标识同前缀（`open:<biz>:<资源>`） |
| 网关路由前缀 | `/open` `/openapp` | `Path=/open/** → lb://mse-open`；白名单放行 `/open/oauth/token` 与 `/open/api/**` |
| 操作日志字典 id | **41** | `sys_dict_data`：sys_oper_module / `open:app` / 开放应用 |
| 开放 API 种子 id | **8001-8022** | `open_api` 表内置端点条目（业务表内 id，22 条） |
| 微服务应用名 | `mse-open` | spring.application.name；`@TenantServiceModule("mse-open")` |

## 四、工程结构蓝图

```
mse-open/
├── pom.xml                  # 父 pom 指向 mse-starter-parent（relativePath 置空）
├── README.md                # 工程主文档（功能与机制权威描述）
├── docs/                    # 本文档集 + open-api-guide.md（对接指南）
├── open-api/                # 注册契约（纯 jar）：业务积木依赖它完成自注册，随 jar 经 SPI 装配
├── open-service/            # 业务积木（SPI 自装配；db/mysql/ 版本化脚本随 jar 启动自动应用）
│   └── src/main/resources/db/mysql/
│       ├── V20261001_01_open__open_base.sql    # base 通道：子应用注册+菜单+权限点（starter-bridge 推送）
│       ├── V20261010_02_open__open_init.sql    # master 通道最终态基线：open_* 业务表（app_key VARCHAR(64)）+ API 种子 8001-8022
│       └── V20261010_03_open__tenant_seed.sql  # per-tenant 通道：平台目录行按租户播种副本
├── open-boot/               # 启动器：micro-starter + open-service（vip.mseapp.boot.open）
├── open-ui/                 # 管理端子应用（dev 3600；base=/sub-apps/open/）
└── docker/nacos/config/mse-open.yaml   # Nacos 服务专属配置留档（Data ID: mse-open.yaml）
```

部署形态：微服务为默认形态（Nacos + Dubbo，网关 `/open` 前缀）；单体聚合在 mse-monolith 引入 `open-service` 即可（`mse.rpc.mode=local`，目录注册容器内直调、分发走反射）。

## 五、平台协作约定（红线）

1. **不改框架区**：一切定制经 SPI 自动装配 + `@ConditionalOnMissingBean` 替换语义实现；基座用户/部门/角色能力经 `api-system` 契约消费，不复制不绕行。
2. **SQL 免手工执行**：`master` 通道随启动自动迁移（`mse.tenant.schema.auto-migrate=true`，失败阻断启动）；`base` 通道由 starter-bridge 就绪即推 mse-system（周期重推自愈）；`per-tenant` 播种随租户初始化应用。
3. **权限点一律挂靠本工程 type=3 页面菜单**（451/452/453/455），参与角色授权分组与租户套餐裁剪；前端按钮用 `v-permission` 按权限码控制。
4. **业务自注册纪律**：业务积木开放能力只声明 `OpenApiContributor`（code 格式 `open:<biz>:<资源>`、path 带业务前缀段、target 指向 `String 方法名(String requestJson)` 契约方法），mse-open 幂等接收——**业务新增开放能力 mse-open 零改动**（详见 [01-功能规划](01-功能规划.md) 第七节与 [open-api-guide.md](open-api-guide.md)）。
5. **安全基线**：生产必须更换 `mse.open.crypt-key`（更换后历史密文不可解，需重置各应用密钥）；凭证有效期与限流 QPS 按应用设置；对外暴露的应用建议开启请求签名并配置 IP/域名白名单。
6. **资源登记**：第三节实际占用值已登记 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md`，后续扩段先查表再占用、用后回写。
