# mse-monitor —— 监控中心（产品文档集）

> **状态：已落地**（工程创建于 2026-09-19；缓存两页合并为单一「缓存监控」、删键权限收敛于 2026-09-24）。
> 本文档集为产品留档，实际落库以工程内版本化脚本为准（`monitor-service/src/main/resources/db/mysql/`）；02/03 中的 id 与脚本逐条对应。

## 一、产品定位

面向**多租户 SaaS 基座**的轻量监控中心：对**服务 / JVM / 慢SQL / 中间件 / 操作系统**做状态探测、指标采集、趋势展示与阈值告警。

**拉模式零侵入**是第一设计原则：对被监控的 Java 微服务只读 `/actuator/health`、`/actuator/metrics/*`、`/actuator/slowsql`（基座 micro/mono-starter 已内置 actuator，网关白名单已放行），被监控服务**不需要任何代码改动**；仅慢 SQL 需在目标服务侧显式开启开关（默认关闭、零开销）。

在本仓库中的形态：**mse-modules 业务工程**（独立 Maven 工程，不进根 pom 聚合），wujie 微前端子应用 + 微服务启动器，积木拼法与 mse-demo / mse-oa 一致：

- `monitor-service`：业务积木（SPI 自装配，Controller / Service / Mapper 随 jar 就绪）
- `monitor-boot`：微服务启动器（micro-starter + monitor-service，纯 `@SpringBootApplication`，`vip.mseapp.boot.monitor`）
- `monitor-ui`：子应用前端（Vue3 + Element Plus + echarts 紧凑型，wujie 嵌入基座）

**平台/租户双端**：监控中心为双端子应用（side=3）——平台端看平台自身（tenant_id=0：网关/基座微服务/中间件全家桶），租户端看本租户目标（按会话租户路由/过滤）。默认套餐**不勾选**，租户使用需平台端「套餐管理」勾选。**在线/离线双形态**（`mse.monitor.offline`，缺省 false=在线）：在线=监控由平台运营；离线（true）=租户离线私有部署形态，监控退化为「本地单一监控世界」（详见 [01-功能规划](01-功能规划.md) 第十二节）。

## 二、文档导读

| 分册 | 内容 | 读者 |
| --- | --- | --- |
| [01-功能规划.md](01-功能规划.md) | 8 大功能模块与功能点清单、在线/离线两形态说明专节 | 产品 / 全员 |
| [02-权限规划.md](02-权限规划.md) | 子应用注册、页面菜单（sys_menu）、权限点（sys_permission）全量清单与编码规范 | 后端 / 前端 |
| [03-角色规划.md](03-角色规划.md) | 建议角色清单、角色 × 模块授权矩阵、数据权限（双端世界 + 离线退化）、套餐可见性 | 后端 / 实施 |
| [04-操作说明书/](04-操作说明书/README.md) | **分角色操作说明书**（总览 + 2 个岗位手册：平台运维管理员 / 租户管理员），含状态字典与常见问题 | 最终用户 / 培训交付 |
| [05-配置手册-零基础版.md](05-配置手册-零基础版.md) | **面向实施/现场 IT 的部署配置指南**：容器一键部署、零侵入与 actuator-token 对齐、在线/离线模式选择、套餐勾选、排查表与上线清单 | 实施 / 现场 IT |

阅读顺序：01 → 02 → 03。02/03 的表格与 SQL 种子逐条对应。**04 为面向最终用户的交付文档、05 为面向实施的零基础部署文档**，均以系统实际界面与配置为准。

## 三、资源分配（实际占用值，alloc-tables.md 已登记）

| 资源 | 值 | 说明 |
| --- | --- | --- |
| 后端端口 `server.port` | **8097** | 微服务形态 monitor-boot；单体聚合不占独立端口（2026-09-19 登记） |
| 前端 dev 端口 | **4010** | monitor-ui vite dev server；构建 `--base=/sub-apps/monitor/` |
| 子应用注册 `sys_micro_app.id` | **10** | name=`monitor`，标题「监控中心」，side=3（平台/租户双端） |
| 页面菜单 `sys_menu.id` | **490-494** | 490=type=1 分组目录；491-494=总览/目标/告警/缓存（旧「缓存管理」495 已随两页合并下线释放） |
| 权限点 `sys_permission.id` | **4301-4310、4312** | 基点 4300（旧 4311 `monitor:cache:query` 已随合并下线释放，接口兼容放行历史权限码） |
| 权限码前缀 | `monitor:` | 格式 `monitor:<模块>:<动作>`，与 oa/mp/fs 等工程同风格 |
| 网关路由前缀 | `/monitor` | `Path=/monitor/** → lb://mse-monitor`（mse-gateway.yaml 留档；监控需登录，无白名单） |
| 微服务应用名 | `mse-monitor` | spring.application.name；`@TenantServiceModule("mse-monitor")` 由装配类声明 |

## 四、工程结构蓝图

```
mse-monitor/
├── pom.xml                  # 父 pom 指向 mse-starter-parent（relativePath 置空）
├── README.md                # 工程主文档（功能权威描述）
├── docs/                    # 本文档集（产品文档）
├── monitor-service/         # 业务积木（SPI 自装配；vip.mseapp.monitor）
│   ├── autoconfigure/       #   Service/Controller AutoConfiguration（imports 登记）
│   ├── controller/          #   target/record/dashboard/alert/notify/redis 六控制器（双端 @SaCheckOr）
│   ├── engine/              #   MonitorScheduler（10s 轮询）+ probe/ 八类探针 SPI
│   ├── service/             #   Target/Collect/Alert(状态机)/Notify/SlowCapture/Dashboard/RedisCache
│   └── resources/db/mysql/  #   三份版本化脚本：base（基座注册）/ init（业务库）/ platform（平台库副本）
├── monitor-boot/            # 启动器：micro-starter + monitor-service（端口 8097）
├── monitor-ui/              # 子应用（dev 4010；base=/sub-apps/monitor/）
└── docker/nacos/config/     # mse-monitor.yaml 服务专属 Nacos 配置留档
```

部署形态：微服务为默认形态（Nacos + Dubbo，网关 `/monitor` 前缀）；单体聚合仅需 `mse-monolith` 引入 `monitor-service` jar（已引入，SPI 自装配，内存态调度单实例部署即可）。

## 五、平台协作约定（落地红线）

1. **不改框架区**：一切定制经 SPI 自动装配 + `@ConditionalOnMissingBean` 替换语义实现。
2. **SQL 免手工执行**：业务表脚本（`-- mse-target: master`）随启动自动迁移；基座注册（`-- mse-target: base`）由 starter-bridge 就绪即推 mse-system 按账本应用（周期重推自愈）；平台库副本（`-- mse-target: base` + `mse-base-scope: platform`）落平台库——平台会话的全部 mon_* 语句经路由拦截器落平台库。
3. **权限点全部挂靠本工程 type=3 页面菜单**（双端共享），参与角色授权分组与租户套餐裁剪；前端按钮用 `v-permission` 按权限码显隐（如删键按钮）。
4. **默认不进租户套餐**：在线形态由平台运营，租户端菜单靠「套餐不勾选」收口；离线私有部署才由实施按需勾选。
5. **资源已登记**：端口/号段占用以 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md` 为准，释放段（495/4311）不再复用于本工程新资源。
