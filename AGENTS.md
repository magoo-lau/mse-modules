# AGENTS.md —— mse-modules 模块区七工程协作约定

本文件面向所有在本仓库工作的 AI 助手与开发者。本仓库自 ai-mse 单仓（源 commit `cc5410a3`）拆分独立，含七个微服务业务积木工程；mse-mes / mse-oa / mse-mp 已拆至各自仓库。

## 一、仓库结构与工程清单

```
mse-modules/
├── README.md / build.sh / build-ui.sh
├── mse-ai/        # ai-api / ai-service / ai-boot / ai-ui        AI 智能体平台（Spring AI 2.x，端口 8094）
├── mse-data/      # data-api / data-service / data-boot / data-ui 数据中台（8093）
├── mse-fs/        # fs-service / fs-boot / fs-ui                  文件存储（RustFS S3 兼容/本地降级，8095）
├── mse-im/        # im-api / im-service / im-boot / im-ui / clients  即时通讯（OpenIM 集成，8099）
├── mse-monitor/   # monitor-service / monitor-boot / monitor-ui   监控中心（平台端子应用，8097）
├── mse-open/      # open-api / open-service / open-boot / open-ui 开放平台（对外窗口，8092）
└── mse-search/    # search-api / search-service / search-boot / search-ui 搜索引擎（Elasticsearch 9，8096）
```

本仓自持三份跨仓契约：`ai-api`（mse-ai）、`open-api`（mse-open）、`search-api`（mse-search）——被 mse-oa / mse-mp / mse-mes 各仓依赖（1.0.0-SNAPSHOT 显式版本），**改动契约需先本仓发版再通知消费仓**。

## 二、工程拼法（对齐基座，硬性要求）

1. **parent = mse-starter-parent**（`<relativePath/>`，经本地/私服仓库解析）——前置：mse-framework 仓先 `sh build.sh`。
2. **固定依赖四件** `starter-web/mybatis/security/rpc`（自动装配随 jar 走）；工具类按需引 common-*；契约积木 api-* 跨模块唯一合法。
3. **SPI 装配**：`XxxServiceAutoConfiguration`（`@AutoConfiguration(before=MseMybatisAutoConfiguration)` + `@MapperScan` + `@ComponentScan` 非 controller 包 + 类标 `@TenantServiceModule("mse-<biz>")`）+ `XxxControllerAutoConfiguration`（`mse.service.<biz>.controller.enabled` 开关），imports 文件登记；启动器纯 `@SpringBootApplication`（`vip.mseapp.boot.<biz>`）。
4. **微服务形态**：boot 依赖 `micro-starter` + 自有 service（+按需 mq-starter）；`application.yaml` 仅应用名 + `spring.config.import`（Nacos 基线真源在 mse-framework 仓 docker/）。
5. **自定义覆盖**：底座默认实现 `@ConditionalOnMissingBean` 可整体替换；全程不修改基座代码。

## 三、租户与事务红线（与基座一致）

- 写操作 `@Transactional(rollbackFor = Exception.class)`；跨服务/跨库全局入口叠加 `@GlobalTransactional`（直接 `@GlobalTransactional` 的 bean 必须组件扫描注册）；一方法跨多库路由只用 `@GlobalTransactional`。
- 事务开启前必须已处于目标世界（事务内 `runAs` 无效）；跨租户后台扫描禁止 @Transactional 包裹；无上下文凭证进门=标识符自带世界段（im 回调 `/im/callback/{tenantId}/{token}` 范式）。
- 并发闸门用 common-core `TenantTaskGates`，经自有装配类 `@Bean` 注册，键在 mse-agent-plugin 仓 `references/alloc-tables.md` 登记。

## 四、SQL 与权限号段

- 版本化脚本：`<biz>-service/src/main/resources/db/mysql/V{yyyyMMdd}_{NN}_<biz>__<描述>.sql`（biz 段=积木名，幂等写法，已应用不改、变更加新版）。
- 权限点挂自有页面菜单；id 号段（权限点/子应用注册/页面菜单）唯一登记处：**mse-agent-plugin 仓 `plugins/mse-dev/references/alloc-tables.md`**（ai=26xx 段等，先查后用、用后登记）。

## 五、能力开放与 AI 接入（自声明、零 SQL 种子）

- 对外开放：`xxx-api` 契约 + `OpenApiContributor` Bean（mse-open 按目录泛化分发，业务零改动接入）——先例 mse-oa / mse-ai。
- AI 反向工具/场景智能体：实现 `RemoteAiTool` / `AiAgentProvider`（ai-api 契约）自注册；模块化查询继承 `AbstractBizQueryTool`；微服务形态 boot 侧经 `DispatchingAiTool` 单门面导出（同 group 只能导出一个）。先例：mse-mes / mse-oa / mse-mp。

## 六、构建、镜像与验证

- `sh build.sh`（可 `MSE_MODULES="mse-fs mse-search"` 子集）；`sh build-ui.sh`（8 个前端工程；im/clients/desktop Electron 单独手工构建）。
- 镜像：各 `<biz>-boot/Dockerfile`（context=本仓根）；本地运行见 `docker/README.md`。
- 验证底线：各工程 `mvn install -DskipTests` 通过；各 UI `npm run build` 通过；涉租户/权限按 docs/verification.md 人工回归。

## 七、提交纪律

每仓独立提交体系；跨仓契约变更：本仓先行 commit，再通知消费仓（mse-oa / mse-mp / mse-mes）适配。
