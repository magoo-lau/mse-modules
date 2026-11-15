# mse-im —— 即时通讯集成 OpenIM（产品规划）

> **状态：一期已落地**（账号桥 / 会话签发 / 工作台 SSO / 实例台账与平台编排 / 机器人 / 审计 / 聊天客户端 Web 全量能力 / 1v1 音视频通话，2026-09-28 全链路自回归通过）。
> 本文档集为产品规划留档，实际落库以工程内版本化脚本为准（`im-service/src/main/resources/db/mysql/`）；02 中的 id 即种子脚本已落地的真实值。

## 一、产品定位

面向**多租户 SaaS** 的即时通讯能力：外接开源 IM 服务端 [open-im-server](https://github.com/openimsdk/open-im-server)（Go，v3.8.3-patch.x），mse 侧只做「**账号桥 + IM 会话签发 + 工作台 SSO**」薄层——**用户体系唯一真源是 ai-mse**（不部署官方账号层 openim-chat），聊天客户端登录走基座账号密码，userID 直接复用 `sys_user.id`。

**多租户隔离模型**：社区版 OpenIM 无多应用概念——**一租户一套完整 OpenIM 实例**（独立端口 / secret / 存储，约 2~4GB 内存），平台经「IM 开通」页编排开通租户专属实例（compose 渲染 + docker CLI + 健康等待，端口块动态分配）；租户也可自助录入自建 / 第三方托管实例。

在本仓库中的形态：**mse-modules 业务工程**（独立 Maven 工程，不进根 pom 聚合），积木拼法与 mse-demo / mse-mes 一致：

- `im-api`：跨服务 RPC 契约（预留包结构）
- `im-service`：业务积木（SPI 自装配，Controller / Service / Mapper 随 jar 就绪）
- `im-boot`：微服务启动器（micro-starter + im-service，端口 **8099**，网关 `lb://mse-im`）
- `im-ui`：子应用前端（Vue3 + Element Plus 紧凑型，wujie 嵌入基座；dev **4030**；**side=3 双端**——租户端 3 页 + 平台端 1 页）
- `clients/web/`：自研聊天客户端（Vue3 + `@openim/wasm-client-sdk`，dev **4100**，直连 OpenIM 实例）
- `docker/openim/`：OpenIM 实例部署物（官方 compose 裁剪，容器名 `im-` 前缀，含 MinIO / LiveKit）

## 二、文档导读

| 分册 | 内容 | 读者 |
| --- | --- | --- |
| [01-功能规划.md](01-功能规划.md) | 10 大功能模块与功能点清单、核心链路（租户开通→实例编排→账号桥→SSO→聊天→审计）、待办规划项 | 产品 / 全员 |
| [02-权限规划.md](02-权限规划.md) | 子应用注册、页面菜单（sys_menu）、权限点（sys_permission）全量清单与编码规范 | 后端 / 前端 |
| [03-角色规划.md](03-角色规划.md) | 角色清单（建议角色，无种子）、角色 × 模块授权矩阵、数据权限（实例按租户隔离） | 后端 / 实施 |
| [04-操作说明书/](04-操作说明书/README.md) | **分角色操作说明书**（总览 + 3 册：平台管理员 / 租户管理员 / 普通用户） | 最终用户 / 培训交付 |
| [05-配置手册-零基础版.md](05-配置手册-零基础版.md) | **面向实施 / 现场 IT 的部署配置指南**：OpenIM 栈前置依赖、一键部署、两种开通方式、Nacos 要点、排查表与上线清单 | 实施 / 现场 IT |
| [mobile-client.md](mobile-client.md) | IM 原生移动端（Android 等）接入约定（三链路复用现有接口，后端零改动） | 移动端开发 |
| [TODO.md](TODO.md) | 已完成能力 + 待办清单（实施排期） | 研发 |
| 仓库根 [docs/tech/openim-integration.md](../../../docs/tech/openim-integration.md) | **权威技术设计文档**（架构、时序、租户隔离模型、风险清单） | 研发 / 架构 |

阅读顺序：01 → 02 → 03；技术实现以仓库根 openim-integration.md 为准。**04 为面向最终用户的交付文档、05 为面向实施的零基础部署文档**，均以系统实际界面与配置为准。

## 三、资源分配（实际占用，已登记 alloc-tables.md）

| 资源 | 实际值 | 说明 |
| --- | --- | --- |
| 后端端口 `server.port` | **8099** | 微服务形态 im-boot；单体聚合不占独立端口 |
| 前端 dev 端口 | **4030**（im-ui）/ **4100**（clients/web 聊天客户端） | vite dev server |
| 子应用注册 `sys_micro_app.id` | **12** | name=`im`，标题「即时通讯」，**side=3 双端**（租户端 + 平台端） |
| 页面菜单 `sys_menu.id` | **516-521** | 516 租户端分组目录（517 IM 设置 / 518 机器人 / 521 工作台配置）；519 平台端目录（520 IM 开通） |
| 权限点 `sys_permission.id` | **4600-4608** | 实例 4600-4602、机器人 4603-4605、平台开通 4606、工作台配置 4607-4608（详见 [02-权限规划](02-权限规划.md)） |
| 权限码前缀 | `im:` | 格式 `im:<模块>:<动作>` |
| 网关路由前缀 | `/im` | `Path=/im/** → lb://mse-im`；白名单 `/im/sso/exchange`、`/im/oauth/token`、`/im/oauth/userinfo`、`/im/workbench/asset/*`、`/im/client/asset/*`、`/im/callback/**` |
| sys_config 参数种子 id | **53201+（未用）** | 当前无参数种子 |
| 微服务应用名 | `mse-im` | spring.application.name；`@TenantServiceModule("mse-im")` |
| OpenIM 实例端口族 | 手动 PoC 栈 **10001/10002/10004/10005/12379/12380 + LiveKit 7880/7881（UDP 7880-7889）**；编排托管实例**端口块动态分配**（block-size=10、首块 port-base=11000，api=块基址 / ws+1 / minio+2 / console+3） | Windows 部署须避开 `netsh excludedportrange` 保留段；详见 alloc-tables「6b OpenIM 实例端口族」 |

## 四、工程结构蓝图

```
mse-im/
├── pom.xml                  # 独立工程，parent=mse-starter-parent（relativePath 置空）
├── README.md                # 工程主文档（端点总表 / 配置 / 客户端能力全景）
├── docs/                    # 本文档集（产品系统规划；mobile-client.md 与 TODO.md 原有保留）
├── im-api/                  # RPC 契约（预留包结构）
├── im-service/              # 业务积木（SPI 自装配；db/mysql/ 版本化脚本：base 通道种子 + master 通道 6 张业务表）
├── im-boot/                 # 启动器：micro-starter + im-service（vip.mseapp.boot.im，端口 8099）
├── im-ui/                   # 子应用（dev 4030；IM 设置 / 机器人 / 工作台配置 / IM 开通四页）
├── clients/web/             # 自研聊天客户端（dev 4100；消息/媒体/通话/工作台全量 UI）
└── docker/
    ├── openim/              # OpenIM 实例部署物（compose 裁剪 + .env + verify.sh + README）
    └── nacos/config/        # mse-im.yaml 专属配置留档（init-configs.sh 导入 Nacos）
```

部署形态：微服务为默认形态（Nacos + Dubbo，网关 `/im` 前缀）；单体聚合 `mse-sample/mse-monolith` 已引 `im-service`（standalone 形态同段配置直连本机实例）。

## 五、平台协作约定（落地红线）

1. **不改框架区**：一切定制经 SPI 自动装配 + `@ConditionalOnMissingBean` 替换语义实现；mse-ui 侧仅登录链路通用扩展（`?ssoCode` 兑换约 30 行）。
2. **SQL 免手工执行**：业务表脚本（`-- mse-target: master`）随启动自动迁移；基座注册（`-- mse-target: base`）由 starter-bridge 自动推送——菜单与权限点种子已全部落地，见 [02-权限规划](02-权限规划.md)。
3. **权限点一律挂靠本工程 type=3 页面菜单**（4600-4608 全部挂 517/518/520/521），参与角色授权分组与租户套餐裁剪；聊天使用类端点（/im/client/**、/im/sso/code、/im/workbench 拉取）为**登录态鉴权、不设权限点**——普通用户零授权即可用。
4. **OpenIM 栈独立于基座中间件**：实例私有 mongo/redis/kafka/minio/etcd 不与基座共用；`OPENIM_SECRET` 与 mse 侧 `mse.im.server.secret` 必须一致；平台账号（tenant_id=0）不参与 IM。
5. **资源用后登记**：手动新增 OpenIM 实例端口段、新菜单 / 权限点须回写 `alloc-tables.md`（4609+ / 522-529 段规则见该表）。
