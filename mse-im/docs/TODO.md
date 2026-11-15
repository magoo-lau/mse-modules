# mse-im 任务清单

> 设计依据：[docs/tech/openim-integration.md](../../../docs/tech/openim-integration.md)（权威技术文档）。本文件只维护「已完成能力」与「待办」两节；两处不一致时以方案文档为准并回改本文件。
> 号段（已登记 `zcode-plugin/plugins/mse-dev/references/alloc-tables.md`）：后端 8099、前端 dev 4030、网关 `/im`（白名单 `/im/sso/exchange`、`/im/callback/**`）、子应用 id 12、菜单 516-518、权限点 4600-4605、sys_config 53201+（未用）、OpenIM 实例端口族见 alloc-tables §6b。

## 已完成能力

- **账号桥与会话签发**：`POST /im/client/session`——userID 复用 `sys_user.id`、懒供给（重复注册 1102 短路 + `update_user_info` 资料同步）、停用用户拒签、平台账号拒绝、admin token 按实例缓存临期刷新。
- **工作台 SSO**：`POST /im/sso/code|exchange`——60s 一次性 code（Redis GETDEL）、绑定目标站点 Origin、同用户限频、兑换标准租户 Sa-Token 会话；mse-ui 路由守卫 `?ssoCode` 自动兑换（`VITE_SSO_EXCHANGE_URL` 可覆盖端点）；网关路由 `/im/**` 与白名单已登记。
- **实例台账 + 租户自助**：`im_instance_config` 每租户一行（secret AES-GCM 密文、出参不回显、留空不改；托管行租户仅可启停）+ `GET/PUT /im/instance`、`POST /im/instance/test`（get_admin_token 验活 + webhook 回调可达性探测，结果回写三列）+ SSRF 白名单校验（`mse.im.ssrf.allow-private-cidrs`）。
- **选址**：台账行（启用）> `mse.im.servers[tenantId]` > 默认段；有行但停用 = 显式关闭不回落。
- **im-ui 子应用**（dev 4030）：「IM 设置」「机器人」两页；base 通道种子（micro_app id 12 / 菜单 516-518 / 权限点 4600-4605），套餐/license 门控随菜单裁剪天然生效。
- **webhook 消费**：`/im/callback/{tenantId}/{token}/{command}`（tenantId 显式路由到租户库、token 校验凭据、未登记组合 404、operationID 去重；**beforeUserRegister 同步拒绝**——非「雪花数字/bot_ 前缀」的注册旁路 actionCode=1）；`afterUserOnline/Offline/KickOff` 维护 **Redis 在线状态**（`GET /im/online[/list]` 查询）+ 上线复核停用即逐端型 `force_logout`；`afterSendSingleMsg/GroupMsg` 触发机器人并落 **im_msg_audit 消息审计**。
- **机器人**：`im_bot` 绑定 mse-ai 智能体（agentCode/触发关键词/群白名单），保存即注册进实例；回复经 `RemoteAiChatService` → `/msg/send_msg` 代发（群内 106 AtText @提问者），AI 失败降级提示。**上下文感知（V20261001_03）**：触发后从 `im_msg_audit` 存档拉本会话最近 N 条（群聊按群、单聊按双向消息流，含机器人自身历史回复）以「昵称: 文本」脚本拼进提问（`ImBotContextService`）——无 AI 侧会话状态，天然多轮、重启无损、可感知群成员间对话；「引用+@」组合消息（114，atUserList 被客户端丢弃）经正文 @昵称 兜底触发；bot 代发消息不再触发任何 bot（防环）；规模经 `mse.im.bot.context-size / context-max-chars` 全局配置，审计行同步落昵称快照（`sender_nickname`）并补会话查询索引。
- **开通平台编排**：`ImProvisionService`——compose 参数化渲染（剥 container_name/固定端口、`ports: []` 空键改写、复制 rpc-auth 覆盖文件）、随机凭据、docker CLI 起栈 + get_admin_token 实测健康等待、孤儿目录与失败自动清场、端口块动态分配；`provision/stop/start/destroy/status` + **upgrade（换镜像 tag 重建）/ backup（mongo archive + minio tar）/ nginx（同域反代配置段生成，子域模板 `domain-template` 可选）** 全生命周期；`PLATFORM_HOSTED` 台账写入（配置子域模板时同时落公网坐标）。
- **OpenIM 部署物**：`docker/openim/`（官方 compose 裁剪去 chat/前端/监控、`im-` 前缀、token 有效期挂载覆盖 7 天）+ `verify.sh` 接口语义验证 + 接口语义速查表。
- **验证基线**：接口语义全部 v3.8.3-patch.15 实测定型（`docker/openim/README.md` 速查表）；本机双实例全链路自回归通过（登录→签发→SSO→台账→机器人（含 mock LLM 端到端：消息→智能体→send_msg 代发）→webhook→在线状态→审计→开通→nginx/备份/升级→停启→销毁→回落）；`mvn install` 与 `npm run build` 全绿。
- **聊天客户端（web）**：`clients/web/`——@openim/wasm-client-sdk（版本对齐服务端）+ mse 登录/会话/工作台三接口 + `OnUserTokenExpired/Invalid/WillExpire` 自动重签重连 + 会话/单聊收发 + 工作台页签（profile 菜单 + ssoCode 免登跳转）；wasm 资产 postinstall 拷贝；dev 4100（`MSE_API_TARGET` 切换后端）。
- **聊天客户端 OpenIM 能力全量补齐（2026-09-28）**：组件化拆分（chat/ 目录，MessageList/MessageItem/bubbles/Composer/call/media/summary）后接入——媒体消息（图片/语音 MediaRecorder≤60s/视频自动截帧/文件，createXXXMessageByFile 经 apiAddr 预签名直传 MinIO，OnProgress 气泡进度条）、@消息（光标选人+@所有人 getAtAllTag）、引用回复（createQuoteMessage+点击定位）、单条/合并/逐条转发（createForward/Merger+预览）、名片（createCardMessage）、链接卡片（custom 协议 `link-card`，机器人可按同协议推送）、emoji 面板、撤回（2 分钟内+群主/管理员）、删除/重发/复制、多行输入+草稿（setConversationDraft）、C2C 已读回执、正在输入（changeInputStates）、对端在线（subscribeUsersStatus）、上滚分页、会话右键（置顶/免打扰/已读/删除）、全局消息搜索（searchLocalMessages）；群管理增强（群名/公告/全员禁言/成员禁言/踢人/转让/退群/解散，部门群隐藏退群解散——contacts 响应新增 `deptGroupIds`）。**好友体系不启用**（组织架构即通讯录，README 有取舍说明）。
- **音视频通话（1v1）**：自研信令（custom 消息 `call` 协议 + isOnlineOnly 在线消息：call/accept/reject/cancel/hangup/busy，30s 超时/占线回 busy）+ LiveKit 媒体——docker 栈新增 `im-livekit`（compose/.env，`LIVEKIT_*`），后端 `mse.im.livekit.{enabled,url,api-key,api-secret}` + `POST /im/client/rtc/token`（手写 HS256 JWT 零新依赖）+ session 响应下发 `rtc{enabled,url}` + 台账 `livekit_url` 列（V20260928_02，开通编排按端口块自动写入）；前端 livekit-client 接入（来电铃声/接听拒接/静音/摄像头开关/计时）。
- **消息审计口径**：`ImMsgAuditService.extractText` 按 contentType 产出可读摘要（[图片]/[语音] xs/[文件] 名/[名片] 昵称/[链接] 标题/[通话] 等），与客户端 summary.ts 同口径；106 @全员标记还原「@所有人」、114 引用附「[回复 引用者: 被引用摘要]」（机器人上下文需感知引用语义）；落库带发送者昵称快照（`sender_nickname`，V20261001_03）。
- **平台端「IM 开通」页**：im-ui 双端（base 种子 V04：菜单 519/520 scope=1、权限点 4606 `im:provision:manage`、micro_app side=3）——开通/探活/停启/备份/升级/nginx/销毁全操作界面。
- **工作台配置**：`im_workbench_item`（V07）+ 租户端「工作台配置」页（菜单 521/权限 4607-4608，V06）——勾选聊天客户端宫格入口 + 图标（emoji/图片 URL）/标题覆盖/排序；客户端 `GET /im/workbench` 拉取（未配置回退全量默认），**点击时才换取一次性 code**（修复批量预签触发限频且 60s 过期导致跳登录页的问题）；工作台为宫格布局（图标+名称瓦片）。

## 待办

- [x] **聊天客户端改造**（web 先行）：`clients/web/`（@openim/wasm-client-sdk + mse 三接口 + 自动重签 + 工作台）；浏览器级收发体验待真实双账号窗口人工走查。
- [x] **应用链路人工回归**：mse-ui SSO 免登在回归环境以 curl 级全分支验证（兑换/重放/跨 Origin/无 Origin）；浏览器视觉回归（多页签/深色）留待日常使用窗口。
- [x] **平台端「IM 开通」管理页**：im-ui 双端 + base 种子（菜单 519/520、权限 4606）——开通/探活/停启/备份/升级/nginx/销毁全操作界面。
- [x] **开通流补全**：`/{id}/nginx` 生成同域反代配置段；`mse.im.provision.domain-template` 子域模板（开通即落 https/wss 公网坐标并生成 nginx 段；未配置时端口直连）。通配证书部署为运维事项（配置段已留注释位）。
- [x] **实例运维**：`/{id}/upgrade`（换镜像 tag 重建 + 健康等待）、`/{id}/backup`（mongo archive + minio tar → `mse.im.provision.backup-dir`）。
- [x] **webhook 深化**：消息审计落 `im_msg_audit`（V05）；`beforeUserRegister` 同步拒绝直连旁路；`afterUserOffline/KickOff` 在线状态回写 Redis + `/im/online` 查询端点。
- [x] **机器人端到端复验**：本地 mock OpenAI 兼容服务 + 真实 ai_model/ai_agent 行跑通「消息回调 → 智能体 → send_msg 代发」全链（顺带修复 mse-ai 两处缺陷，见下）。
- [ ] **更多端 demo**（android/ios/uniapp/flutter 按需，SDK 初始化层复用 web 端封装）：接入约定见 [mobile-client.md](mobile-client.md)（登录/会话/工作台 WebView 三链路复用现有接口，后端零改动）。
- [ ] **租户套件捆绑交付**：collect-kit push-images 携带 OpenIM 全家桶镜像 + standalone 默认实例配置段。
- [ ] **通话人工回归**：双浏览器账号 1v1 音视频（LiveKit UDP 在 Docker Desktop 的可达性、不通则走 ICE-TCP 7881 兜底）；信令 isOnlineOnly 实测确认不进会话（若进会话则信令 contentType 换 120 CustomOnlineOnly）。
- [ ] **位置消息地图**（低优）：109 现仅渲染经纬度+描述骨架；内网环境无外网瓦片，引入 Leaflet+OSM 需按部署形态可选。

## 依赖与约束

- 框架区先在根目录 `mvn install -DskipTests`（Spring Boot 4 升级分支基线）。
- OpenIM 锁定 v3.8.3-patch.x（Docker Hub 镜像 tag 滞后于 release，部署锚 patch.15）；升级走独立租户实例灰度。
- 不引入 openim-chat（用户体系唯一真源 = ai-mse）；平台账号不参与 IM；不做云服务来源（官方无公有云 SaaS）。
- 提交纪律：mse-im 工程与 clients 为 biz 分区；mse-ui 为 framework 分区；文档为 docs 分区。
