# 架构与工程拼法

## 一、标准结构（每工程）

```
mse-<biz>/
├── pom.xml          # parent = mse-starter-parent（<relativePath/> 仓库解析）；properties 声明跨仓 api 版本
├── <biz>-api/       # 对外契约（接口 + Serializable DTO；依赖仅基座 common-core）——有对外契约的工程才有
├── <biz>-service/   # 业务积木（纯 jar，SPI 自装配；db/<方言>/ 版本化脚本）
├── <biz>-boot/      # 启动器（vip.mseapp.boot.<biz>；micro-starter + 自有 service；Dockerfile + k8s）
└── <biz>-ui/        # wujie 子应用（file:../../../mse-framework/mse-ui-shared）
```

## 二、SPI 装配（对齐基座，硬性要求）

- `<Biz>ServiceAutoConfiguration`：`@AutoConfiguration(before = MseMybatisAutoConfiguration.class)` + `@MapperScan` + `@ComponentScan` 非 controller 包 + `@TenantServiceModule("mse-<biz>")`；契约实现 `@Bean + @ConditionalOnMissingBean`。
- `<Biz>ControllerAutoConfiguration`：`@ConditionalOnWebApplication(SERVLET)` + `@ConditionalOnProperty(mse.service.<biz>.controller.enabled, matchIfMissing=true)`。
- imports 文件登记两 FQCN；启动器纯 `@SpringBootApplication`。

## 三、跨仓契约归属（本仓自持三份）

| 契约 | 归属工程 | 消费仓 |
|---|---|---|
| ai-api | mse-ai | mse-oa / mse-mp / mse-mes（AI 反向工具、智能体描述、biz 查询） |
| open-api | mse-open | mse-oa / mse-mp / mse-mes（开放目录贡献、开放能力分发） |
| search-api | mse-search | mse-oa（表单/流程全文检索源） |

版本 1.0.0-SNAPSHOT 显式声明于消费仓 properties。**改契约先本仓发版再通知消费仓**。

## 四、能力开放与 AI 接入（自声明、零 SQL 种子）

- **开放平台**：业务实现 `OpenApiContributor` Bean（code/name/path/httpMethod/target 清单）→ mse-open 目录自动收录泛化分发（管理端可启停）；业务新增开放能力 mse-open 零改动。
- **AI 反向工具**：实现 `RemoteAiTool`（ai-api）`@Component`，单体启动扫描 / 微服务周期重推自动登记；多工具 boot 侧 `DispatchingAiTool` 单门面导出（同 group 一个，group=应用名 Dubbo 导出）。
- **AI 场景智能体**：实现 `AiAgentProvider` 返回 `AiAgentDescriptor` 清单；agentCode 冲突防护（他方已登记跳过，租户自有行不触碰）；「场景→智能体」映射存自有 `xxx_ai_agent_map` 表，开关 sys_config `xxx.ai.mode`。
- **模块化查询**：`AiBizModuleProvider`（moduleCode/keywords/表前缀）+ 继承 `AbstractBizQueryTool`（本进程内实时元数据检索 + 受控 text2sql，前缀白名单 ∩ deny 黑名单，tenant_id/deleted 执行期注入）。

## 五、租户红线（与基座一致）

写操作 `@Transactional(rollbackFor = Exception.class)`；跨服务/跨库入口叠加 `@GlobalTransactional`（组件扫描注册）；事务开启前必须已处于目标世界；跨租户扫描禁止 @Transactional 包裹；`runIgnore` 仅平台登记源与全局视图两类；无上下文凭证进门=标识符自带世界段（im 回调 `/im/callback/{tenantId}/{token}` 范式）；自建线程池经 `MseExecutors`。

## 六、SQL 与号段

- `db/<方言>/V{yyyyMMdd}_{NN}_<biz>__<描述>.sql` 幂等脚本；已应用不改、变更加新版；target/base-scope 头指令见 framework 仓 docs/schema-migration.md。
- 权限点挂自有页面菜单；号段登记：mse-agent-plugin 仓 references/alloc-tables.md（mp=6xxx、open=7xxx、fs=41xx、search=42xx、monitor=43xx、data=9501+ 等）。
