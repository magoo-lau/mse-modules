# mse-modules —— 模块区（都以微服务的方式组织）

本目录存放基于 `mse-framework` 底座组装的**独立业务工程**。约定与示例工程 `mse-sample/mse-demo/` 一致：

- 每个业务工程是独立 Maven 工程（自带父 pom），**不加入** `ai-mse` 根 pom 的 `<modules>`；
- 通过 `dependencyManagement` import 框架 BOM `vip.mseapp:mse-dependencies` 消费底座发布件（先在根目录 `mvn install`）；
- 模块命名以**业务名-**开头：`xxx-service`（业务积木，纯 jar，**必须经 Spring Boot SPI 接入**）+ `xxx-boot`（微服务启动器，启动类在 `vip.mseapp.boot.<biz>`，纯 `@SpringBootApplication`）；
- `xxx-boot` 为**纯微服务启动器**：依赖 `micro-starter`（微服务形态入口启动器：Nacos + Dubbo + starter-bridge 契约消费桥接 + Seata 内置 + 三方言驱动）+ 自有业务积木，**不依赖基座 service-system/platform/auth**——登录/系统管理/租户管理的 HTTP 接口收敛在基座微服务（mse-auth/mse-system/mse-platform），业务服务经契约消费基座数据，避免每个微服务重复暴露基座端点与重复导出基座 Dubbo provider；本地仅 `application.yaml`（应用名 + spring.config.import 引共享 `mse-micro-common/db/dubbo.yaml` 与服务专属 Data ID），数据源/Redis/Sa-Token/Dubbo 等统一在 Nacos 管理，`mse.rpc.mode` 由 micro-starter 默认 dubbo，启动即注册、由网关 `lb://mse-xxx` 路由；服务专属配置留档在工程自己的 `docker/nacos/config/<应用名>.yaml`，经 `CONFIG_DIR=<该目录> sh mse-framework/docker/nacos/init-configs.sh` 导入 Nacos；要聚合单体时把业务积木 jar（如 `oa-service`）聚进单体启动器（参考 `mse-sample/mse-monolith/`），不依赖 Nacos；
- 前端形态：服务专属子应用放本工程（`xxx-ui`，复制 `mse-sample/mse-ui-sub` 模板），多个服务共享的前端放本目录 `modules-ui/`（暂无）；在基座 `mse-framework/mse-ui/src/micro/apps.ts` 注册表或平台端「子应用管理」登记；
- 专属表结构与权限点为业务工程 jar 内版本化迁移脚本（`<biz>-service/src/main/resources/db/<方言>/`（目标由 -- mse-target: 头指令声明：master 业务主库 / base 基座库），随启动 auto-migrate / starter-bridge 推送自动应用，免手工执行），不改动基座 `service-system`/`service-platform` 自带的 `db/<方言>/` 基线脚本；端口/权限点/菜单/网关前缀等 id 分段统一见 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md`（先查表选号、用后登记）。

组装细则与 SPI 接入标准见根目录 `AGENTS.md`。

## 当前工程

| 工程 | 说明 | 子应用 |
| --- | --- | --- |
| `mse-oa` | OA 协同办公：自定义表单 + Flowable 工作流（仿钉钉简易流程设计器）；业务积木按职责拆为 `oa-form` + `oa-bpm`，`oa-service` 聚合两者并提供 OaOrgService 基座桥接（SPI 自装配） | `oa-ui`（dev 3400） |
| `mse-mp` | 微信公众号管理：多账号接入 + 回调处理 + 粉丝/消息/菜单/自动回复 | `mp-ui`（dev 3500） |
| `mse-open` | 业务中台/开放平台：应用凭证 + 开放 API 鉴权链，把基座用户能力开放给其他系统 | `open-ui`（dev 3600） |
| `mse-data` | 多租户数据中台：数据源管理 + 数据集成同步（ODS）+ 元数据/数据地图 + 数据服务 API | `data-ui`（dev 3700） |
| `mse-ai` | AI 智能体平台：模型中心 + 对话 + 智能体 + 工具中心 + RAG 知识库 + MCP + 开放 API | `ai-ui`（dev 3800） |
| `mse-fs` | 文件存储服务：统一存储抽象（RustFS 1.x S3 兼容 / 本地文件系统可配置回退）+ 租户隔离（元数据行级 + 物理键前缀）+ RPC 契约 | `fs-ui`（dev 3900） |
| `mse-search` | 搜索引擎服务：Elasticsearch 9.x + 可搜索源自注册（解耦）+ 定时增量扫描分片 + MQ 实时事件 + 三层权限过滤（租户/权限码/个人数据范围）+ 闲时任务；基座首页搜索卡片与顶栏全局搜索 | `search-ui`（dev 4000） |
| `mse-monitor` | 监控中心：服务（/actuator/health 拉模式零侵入；目标服务开启 actuator 令牌鉴权时探针自动携带（mse.monitor.actuator-token，与 MSE_ACTUATOR_TOKEN 同源））/ 中间件（MySQL/Redis/RocketMQ）/ 操作系统状态探测与指标采集 + 阈值告警（站内信/钉钉/企微/飞书 Webhook 通知，通知设置界面维护） | `monitor-ui`（dev 4010，平台端） |
| `mse-mes` | MES 制造执行系统：基础数据建模（工厂/车间/产线/工位/物料/BOM/工艺路线/日历班次）+ 生产订单/排产/派工/报工（质检点自动生成检验单、批次与线边仓联动、审核驳回数量回冲）+ 质量（方案/检验/不良处置/异常）+ 设备（台账/点保/维修）+ 线边仓领退料 + 批次正反向追溯 + 看板统计；产品规划见工程 docs/（二期集成与 AI、三期 IoT 已预留号段） | `mes-ui`（dev 4020） |
| `mse-im` | 即时通讯集成（OpenIM v3.8）：聊天客户端登录走 ai-mse 账号体系（不部署 openim-chat，userID 直用 sys_user.id）+ IM 会话签发 + 工作台一次性 code 免登跳转各模块 + 实例台账（租户自助/平台开通编排一租户一套实例）+ 机器人（mse-ai 智能体代答）；技术文档见 docs/tech/openim-integration.md，任务清单见工程 docs/ | `im-ui`（dev 4030，租户端） |
