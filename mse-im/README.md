# mse-im —— 即时通讯集成（OpenIM）

把开源 IM 服务端 [open-im-server](https://github.com/openimsdk/open-im-server)（Go，v3.8.3-patch.x）接入 ai-mse：**用户体系唯一真源是 ai-mse**（不部署 openim-chat），聊天客户端登录走基座账号密码，mse-im 负责「账号桥 + IM 会话签发 + 工作台 SSO」。

权威设计文档：[docs/tech/openim-integration.md](../../docs/tech/openim-integration.md)（仓库根 docs/，含架构、时序、租户隔离模型、风险清单）；实施排期见 [docs/TODO.md](docs/TODO.md)。

## 模块结构

| 模块 | 职责 |
| --- | --- |
| `im-api` | 跨服务 RPC 契约（预留包结构） |
| `im-service` | 业务积木（纯 jar，SPI 自装配）：OpenIM 客户端、实例选址、会话签发、SSO 一次性 code、工作台入口与外部应用免登授权、用户资料与自设头像；master 通道业务表（im_instance_config / im_bot / im_msg_audit / im_workbench_item / im_user_profile）随 jar 版本化脚本自动建表 |
| `im-boot` | 微服务启动器（`micro-starter` + `im-service`，端口 8099，网关 `lb://mse-im`） |
| `clients/web/` | 自研聊天客户端（Vue3 + `@openim/wasm-client-sdk`，dev 4100）：消息收发/媒体/通话全量 UI，见下文「聊天客户端能力」 |
| `clients/desktop/` | 桌面客户端（Electron 壳）：服务器设置窗口 + 本地内置 web（回环加载，不经服务器）+ 托盘常驻 + 原生消息提醒，见其 [README.md](clients/desktop/README.md) |
| `docker/openim/` | OpenIM 实例部署物（官方 compose 裁剪：去 chat/前端/监控，容器名 `im-` 前缀，含 MinIO 对象存储与 LiveKit 音视频），见其 [README.md](docker/openim/README.md) |
| `docs/TODO.md` | 任务清单（已完成能力 + 待办） |

单体聚合：`mse-sample/mse-monolith` pom 引 `im-service` 即得（`mse.rpc.mode=local`）；standalone 形态同段配置直连本机实例。

## 端点

| 端点 | 鉴权 | 说明 |
| --- | --- | --- |
| `POST /im/client/session` | 登录态 | IM 会话签发：`{platformID}` → `{userID, imToken, apiAddr, wsAddr, expireAt, nickname, faceURL, rtc{enabled,url}}`。userID = `sys_user.id`；懒供给（首登注册，已注册同步资料）；停用用户拒签；platform 账号拒绝。签发后异步触发部门群保障（防抖对账 + 保障本人已入群）。`rtc` 为音视频通话坐标（LiveKit），enabled=false 客户端隐藏通话入口。`faceURL` 为生效头像三态：IM 自设（im_user_profile）> 基座 `sys_user.avatar`（含站内相对路径，客户端 assetUrl 解析）> 空由客户端首字母兜底 |
| `GET /im/client/user/{userId}/profile` | 登录态 | 聊天对象资料卡：`{userId, username, nickname, avatar(生效头像), mobile, email, deptPath}`（基座 RPC 拼装，仅启用用户；机器人资料客户端走通讯录 bots 集，不设端点） |
| `POST /im/client/profile/avatar`、`POST /im/client/profile/avatar/reset` | 登录态 | 自设头像上传（≤2MB png/jpg/jpeg/gif/webp，multipart）/ 恢复默认（回落基座头像），均返回生效头像地址；落 `im_user_profile.face_url`（重置置空不删行）并即时推送 OpenIM（他端消息气泡头像随之同步） |
| `GET /im/client/asset/{name}` | **免登录（网关白名单）** | 自设头像本地兜底素材输出（mse-fs 模式走 `/fs/asset/**` 不经本端点；文件名白名单定型防穿越） |
| `POST /im/client/rtc/token` | 登录态 | 音视频通话房间 token：`{room}` → `{token, url}`（room = 通话 callId，双端各调一次以本人身份进同一 LiveKit 房间）。信令本身走 OpenIM custom 在线消息，端点只管媒体房间准入（手写 HS256 JWT，零新依赖） |
| `GET /im/client/contacts` | 登录态 | 通讯录全量：`{depts(含 leaderUserId 负责人), users(启用), bots(启用机器人), deptGroupIds(部门群 id 集)}`——部门/用户经 `RemoteOrgService` 契约（无数据权限过滤，通讯录全员可见），机器人读 `im_bot`；`deptGroupIds` 供客户端隐藏部门群的「退群/解散」入口（对账会拉回） |
| `POST /im/client/contacts/ensure` | 登录态 | 按需注册：`{userIds}` → 注册进实例并同步资料（对未登录过 IM 的用户发起单聊/建群前兜底；停用用户跳过） |
| `POST /im/client/share` | 登录态 | 页面分享：`{userIds?, groupIds?, title, desc?, url}` → 以当前用户身份把 app-share 页面卡片（custom 110）发给联系人/群聊，返回送达目标数；发送人与收件人未注册 IM 自动兜底注册，群目标校验发送人在群内；url 必须绝对 http(s)（联系人 ≤50、群 ≤20） |
| `POST /im/client/group` | 登录态 | 建群：`{name?, memberUserIds, botUserIds?}`（群主=当前用户，成员/机器人同一请求带入）；群名缺省按成员昵称生成；机器人进群即自动追加群白名单（幂等，空白名单=所有群无需写） |
| `POST /im/client/group/invite` | 登录态 | 邀请进群：`{groupId, userIds, botUserIds?}`——**仅群主(100)/群管理员(60)** 可邀请（服务端按 OpenIM 群角色强制校验）；已在群内的自动过滤 |
| `GET /im/dept-group`、`POST /im/dept-group/sync` | 租户端 `im:instance:query` / `im:instance:save` | 部门群映射列表（部门/群名/群主/成员数）/ 手动全量对账：新部门建群、改名同步、缺失成员补拉、多余成员踢出（群主与 bot_ 除外）、部门删除解散、群主随负责人转让 |
| `POST /im/sso/code` | 登录态 | 工作台 mse 菜单入口跳转：`{targetUrl}` → `{url(带 ssoCode), expiresIn}`。60s 一次性 code（Redis），绑定签发者与目标站点 Origin，限频 30 次/分（`mse.im.sso.issue-rate-limit`） |
| `POST /im/sso/exchange` | **免登录（网关白名单）** | `{code}` → 标准租户会话（`LoginResult` 同构，mse-ui 路由守卫检测 `?ssoCode` 自动兑换）。GETDEL 原子取删 + Origin 站点比对 |
| `GET/PUT /im/instance`、`POST /im/instance/test` | 租户端权限 | 实例台账自助（每租户一行）：脱敏回显（含 webhook 回填地址）/ 保存（secret 留空不变；托管行仅可启停）/ 连通测试回写三列 |
| `GET/POST /im/bot`、`DELETE /im/bot/{id}` | 租户端权限 | 机器人绑定（bot_ 账号 ↔ mse-ai 智能体），保存即注册进实例；配套 `GET /im/bot/agent-options`（绑定智能体下拉：经 ai-api 契约取本世界已发布智能体，含头像）与 `POST /im/bot/asset`（机器人头像素材上传 ≤2MB，复用 ImAvatarStorage 存储，本地兜底走既有 `/im/client/asset/{name}` 免登输出）；管理页头像缺省带出绑定智能体的头像——emoji 经前端 canvas 画 128×128 PNG 上传成真实 URL（OpenIM face_url 需图片地址），http(s) 与 /fs/、/im/ 地址直接用，其余站内路径转传，手动选图标/上传后解除跟随 |
| `GET /im/workbench`、`GET /im/workbench?all=true` | GET 登录态 / all=true 需 `im:workbench:query` | 工作台入口视图：客户端拉启用行（未配置回退全量）；按入口 `role_ids` 角色绑定过滤（NULL/空=全员可见，非空=与本人角色有交集）；配置页拉全量（含停用、不过滤角色） |
| `GET /im/workbench/roles` | 租户端 `im:workbench:query` | 本租户启用角色清单（入口「可见角色」下拉候选） |
| `PUT /im/workbench` | 租户端 `im:workbench:save` | 工作台入口保存（覆盖式）：启用/停用/标题/图标/排序/打开方式/可见角色（roleIds 非空逐个校验属本租户启用角色）；外部应用入口（entryType=1）新建免登授权行时生成 app_key/app_secret，明文经响应 `secrets` 仅返回一次 |
| `POST /im/workbench/jump` | 登录态 | 外部应用入口跳转：`{id}` → `{url, expiresIn}`。直连返回原 URL；免登授权签发一次性授权码（默认 300s，限频 30 次/分）拼到回调地址。**角色可见性复核**：入口绑定角色而本人无交集时 403（防绕过客户端直调） |
| `POST /im/workbench/secret/reset` | 租户端 `im:workbench:save` | 重置免登授权密钥：`{id}` → `{path, title, appKey, appSecret}`（app_key 不变，secret 换新，明文仅本次返回） |
| `POST /im/workbench/asset`、`GET /im/workbench/asset/{name}` | POST 租户端 `im:workbench:save` / GET **免登录** | 工作台图标上传（≤2MB png/jpg/jpeg/gif/webp）/ 本地兜底素材输出（mse-fs 模式走 `/fs/asset/**`，本地模式 GET 需网关白名单） |
| `POST /im/oauth/token`、`GET /im/oauth/userinfo` | **免登录（网关白名单）** | 外部应用免登授权（OAuth2 授权码模式，见下节） |
| `POST /im/callback/{tenantId}/{token}/{command}` | **免登录（tenantId 路由 + token 凭证）** | OpenIM webhook 消费：上线复核停用即踢线（force_logout）、消息命中机器人 → mse-ai 回复 → send_msg 代发；operationID 去重 |
| `POST/GET/DELETE /im/provision/{tenantId}[...]` | 平台权限 | 开通平台：编排起第二套 OpenIM 栈（compose 渲染 + docker CLI + 健康等待）写 PLATFORM_HOSTED 台账；stop/start/destroy/status 生命周期 |

## 通讯录 / 群组 / 部门群

- **通讯录**（聊天客户端「联系人」页）：组织架构树（部门折叠、用户挂部门、搜索扁平匹配）+ **机器人分组**（公众号式，独立于人的列表）+ 我的群聊。数据源 = 基座组织契约，无需好友关系即可对任意在职用户/机器人发起单聊（先经 ensure 注册进实例）。
- **建群/邀请**：客户端多选人 + 机器人发起群聊（群主=发起人）；群资料抽屉展示成员与角色（群主/机器人标签），邀请按钮仅群主/群管理员可见，服务端二次强制校验。落 OpenIM 管理端 API（`/group/create_group` 等封装在 `OpenImClient`，接口形态经 v3.8.3-patch.15 实例实测：create_group 必须显式 `groupType=2`、踢人路由为 `/group/kick_group`、set_group_info 需包 `groupInfoForSet`）。
- **部门群**（`im_dept_group` 映射）：按部门自动建群「{部门名}群」，**群主 = 部门负责人**（基座「系统管理→部门管理」维护的 `sys_dept.leader_user_id`，经 `OrgDeptDTO.leaderUserId` 契约透出），未设置/已停用回落租户管理员；成员随部门在职名单对账收敛（缺失补拉+顺带注册、调离踢出，`bot_` 与群主不踢）；部门改名同步群名，部门删除解散群并删映射行。触发：用户签发 IM 会话（10 分钟 Redis 防抖，失败释放锁下次重试）或 im-ui「IM 设置→部门群」手动同步。

> **好友体系取舍**：OpenIM SDK 自带好友关系（申请/同意/黑名单），但企业 IM 场景通讯录即全员组织架构（微信企业版同款），引入好友申请流反而与「对任意在职用户发起单聊」的组织语义冲突——**决定不启用好友体系**，SDK 相关 API 不接、UI 不提供入口。

## 聊天客户端能力（clients/web）

自研客户端直连 OpenIM WASM SDK（`/im/client/session` 下发 apiAddr/wsAddr/imToken），不集成官方 UI 组件库；深浅双主题自研 CSS 变量。能力全景（v3.8.3-patch.15.1 SDK 实测对齐）：

| 类别 | 能力 |
| --- | --- |
| 登录态 | 认证存档（localStorage `im-web-auth`：Sa-Token + 登录名）：刷新/重开浏览器静默恢复登录（`/im/client/session` 重签 IM token 直连），Sa-Token 失效（默认 30 天）或用户停用自动清档回登录页并预填账号；运行期 IM token 过期自动重签，重签失败即 mse 会话已失效 → 自动登出 |
| 消息类型 | 文本(101)、图片(102)、语音(103，≤60s 录音)、视频(104，自动截帧封面)、文件(105)、@消息(106，光标触发选人 + @所有人)、合并转发(107)、名片(108)、位置(109，仅渲染骨架不做地图)、自定义(110：链接卡片协议 + 页面分享卡片 + 通话信令)、引用回复(114)、emoji 面板（unicode 直出文本） |
| 消息操作 | 撤回（自己 2 分钟内，群主/管理员不限，服务端校验）、删除、失败重发、复制、引用、单条转发、多选合并/逐条转发、合并消息预览、引用点击定位原消息、图片大图查看/视频播放、文件/图片下载 |
| 会话 | 置顶、免打扰（recvMsgOpt）、标记已读、删除会话、草稿（SDK 持久化）、未读角标、上滚分页历史、全局消息搜索（searchLocalMessages） |
| 状态 | 单聊已读回执（我方最后一条「已读/未读」）、正在输入（changeInputStates）、对端在线状态（subscribeUsersStatus 订阅当前会话对端） |
| 资料卡 | 用户（`/im/client/user/{id}/profile`：账号/部门路径/手机/邮箱 + 生效头像 + 发消息）、机器人（通讯录集：bot id/智能体编码）、本人（同用户另含头像自设）。入口：rail 自己头像、联系人/机器人头像、群资料抽屉成员行、消息发送者头像、单聊/群聊会话头名称 |
| 头像三态 | IM 自设（rail 本人头像点击上传/恢复，≤2MB，即时推 OpenIM）> 基座 `sys_user.avatar`（站内相对路径经 assetUrl 加 `/api` 前缀解析，与基座 resolveAssetSrc 同款）> 首字母兜底；存储 mse-fs 公开素材通道优先、本地磁盘兜底（`mse.im.avatar.storage/dir`） |
| 群管理（群资料抽屉） | 群名/群公告编辑、全员禁言、成员禁言（1h/24h/解除）、踢人、转让群主、退群、解散（群主）——服务端按 OpenIM 群角色校验；部门群隐藏退群/解散（对账自动维护）；会话头群名点击亦可打开 |
| 音视频通话（1v1） | 语音/视频呼叫，自研信令（custom 消息 + `isOnlineOnly` 在线消息，不落库不进会话）：call/accept/reject/cancel/hangup/busy 六态；呼叫 30s 超时、通话中来电回 busy；媒体走 LiveKit 房间（token 经 `/im/client/rtc/token`，mse-im 手写 HS256 签发） |

**媒体上传链路**：`createXXXMessageByFile` + `sendMessage` —— SDK 经 OpenIM apiAddr `/object/*` 取预签名 URL 后**浏览器直传 MinIO**（栈内已有，`MINIO_EXTERNAL_ADDRESS` 必须浏览器可达），进度经 `OnProgress` 事件映射到气泡进度条；`publicRead=false` 读取走 `/object/<key>` 302。生产需保证 MinIO 与 OpenIM apiAddr 同域名反代放行。

**链接卡片协议**（customElem.data JSON，机器人/外部系统可按此推送）：`{"customType":"link-card","title":"…","desc":"…","url":"https://…","thumbUrl":"https://…"}`；页面分享卡片（工作台内嵌页「分享」发出，服务端 `/im/client/share` 以发送者身份代发）：`{"customType":"app-share","title":"…","desc":"…","url":"http(s)://…/sub/{app}{path}?{状态query}"}`——AppShareBubble 渲染，点击仅当 url 与租户工作台基址（webBase）同源才签发一次性 `ssoCode` 免登 + `bare=1` 新窗口直达（防任意卡片骗取签发者会话），不同源按普通外链直开；通话信令：`{"customType":"call","sig":"call|accept|reject|cancel|hangup|busy","callId":"uuid","media":"audio|video","room":"=callId","from":{…},"ts":…}`。

## 桌面客户端（clients/desktop）

`clients/web` 的 Electron 桌面化：**界面产物内置应用本地**（回环 `127.0.0.1` 静态服务器加载 `dist-desktop` 构建，不经服务器），首启弹「服务器地址」设置窗口、登录页与主界面设置弹层均可随时回设置界面，关闭=最小化到托盘、新消息系统通知（点击回主窗口直达会话）+ 任务栏未读叠标/闪烁。web 侧桌面差异项经 `window.imDesktop` 桥守卫（`src/desktop.ts`：服务器地址注入 + axios IPC 转发绕 CORS），浏览器版零感知。构建/打包/设计决策（回环 http 而非 file:// 的原因、窗口复用红线等）见 `clients/desktop/README.md`。

## 选址优先级

**台账 DB 行（启用）> `mse.im.servers[tenantId]` 配置 > `server` 默认段**；台账行存在但停用 = 该租户 IM 显式关闭（不回落）。secret 经 ImCrypt（AES-256-GCM）落库，任何出参不回显；租户录入的 api-url 经 SSRF 校验（`mse.im.ssrf.allow-private-cidrs` 白名单放开内网实例）。

## 配置（mse.im.*）

微服务形态在 Nacos `mse-im.yaml`（留档：`docker/nacos/config/mse-im.yaml`），单体/standalone 写各自 application.yaml：

```yaml
mse:
  im:
    enabled: true
    server:                     # 默认实例（standalone/单实例只配这段）
      api-url: http://127.0.0.1:10002     # mse-im → OpenIM admin REST（内网）
      ws-url: ws://127.0.0.1:10001
      public-api-url: https://im.xxx.com/api   # 下发客户端（同域名反代），缺省回落 api-url
      public-ws-url: wss://im.xxx.com/msg_gateway
      secret: ${MSE_IM_SECRET:}           # 与 OpenIM share.yml secret 一致，缺省空 = 使用时报错
      admin-user-id: imAdmin
    servers:                    # 按租户覆盖（key=租户编号）：多租户绑多实例 = 改这里
      # "2": { api-url: ..., public-api-url: ..., public-ws-url: ..., secret: ... }
    sso: { code-ttl-seconds: 60, issue-rate-limit: 30, allow-empty-origin: false }
    workbench: { icon-dir: ./workbench-icons, auth-code-ttl-seconds: 300, access-token-ttl-seconds: 7200, jump-rate-limit: 30 }
    avatar: { dir: ./im-avatar-assets }   # 用户自设头像本地兜底目录（存储形态开关 mse.im.avatar.storage=auto/fs/local）
    client: { timeout-ms: 5000, admin-token-refresh-ahead-seconds: 600 }
    livekit:                    # 音视频通话（1v1，LiveKit RTC；enabled=false 隐藏通话入口）
      enabled: true
      url: ws://127.0.0.1:7880  # 信令地址（台账行 livekit_url 优先，开通编排自动写入实例端口）
      api-key: mse-devkey       # 与 LiveKit keys 配置一致
      api-secret: ${MSE_IM_RTC_SECRET:}
      token-ttl-seconds: 3600
```

选址优先级：`servers[tenantId]` > `server`；`enabled=false` 或缺配置在使用时明确报「未开通」（启动不阻断）。`/im/sso/exchange` 与 `/im/**` 路由已登记进 `mse-framework/docker/nacos/config/mse-gateway.yaml`。

## 快速联调

1. **起 OpenIM 实例**：`cd mse-modules/mse-im/docker/openim && docker compose up -d`（端口/裁剪说明见该目录 README）。
2. **配 secret**：把 `.env` 的 `OPENIM_SECRET` 同值配到 mse 侧 `mse.im.server.secret`（环境变量 `MSE_IM_SECRET`）。
3. **接口语义速查**：`./verify.sh` —— 输出注册/重复注册/查询/签发/踢线/改资料的原始响应（结论表见该目录 README）。
4. **应用链路**：登录拿 Sa-Token → `POST /im/client/session` → 客户端 SDK `login({userID, token, apiAddr, wsAddr})`；工作台跳转 → `/im/sso/code` → 浏览器打开带 `?ssoCode=` 的 mse 地址即免登。

## 号段占用（已登记 zcode-plugin/.../alloc-tables.md）

后端端口 8099、网关前缀 `/im`（白名单 `/im/sso/exchange`、`/im/oauth/token`、`/im/oauth/userinfo`、`/im/workbench/asset/*`、`/im/client/asset/*`、`/im/callback/**`）、前端 dev 4030、子应用 id 12、菜单 516-521、权限点 4600-4608、权益项 4651（feature.im.notify）——全部已登记 alloc-tables.md；sys_config 53201+ 未用。

## 通知渠道接入（框架通知中枢，2026-10-16）

mse-im 是首个通知渠道提供方：`ImNotifyChannelFacade`（im-service `notify` 包）实现框架契约 api-notify 的 `RemoteNotifyChannel`，向**框架通知中枢（基座 service-system）**自注册 `im` 渠道——系统通知机器人（bot_notify，首次投递幂等注册进实例）向接收人单聊代发：通知带 `linkUrl` 发 app-share 页面卡片（custom 110，客户端 chat/custom.ts 协议）——linkUrl 为基座内相对路径（`/sub/...`，业务方只拼自己的前端路由）时经 `ImWebBaseResolver` 按租户 webBaseUrl（台账行 > 全局配置 > 缺省）补全绝对地址，收件人点击与 webBase 同源即签发一次性 code 免登直达基座页面（如 OA 待办直达审批页）；已是绝对 http(s) 地址原样透传（不同源不签 code 按外链直开）；无 linkUrl 发纯文本（101）；接收人未注册 IM 时先经账号桥补注册（`ImContactService.ensureUsers`），单人失败续发其余、全败抛出由中枢记录。渠道目录/租户授权/异步调度/投递日志均在框架层（`docs/tech/notify.md`）。

- **登记通道**：微服务形态 im-boot `ImNotifyExportConfiguration` 以 group=应用名 Dubbo 导出门面 + api-notify 推送器把渠道描述周期推送至基座 mse-system（幂等 upsert + 重推自愈）；单体聚合形态框架中枢直接收集容器内 Bean，推送器自检闲置。
- **租户授权（授权制渠道）**：`feature.im.notify` 权益开关（BOOL 默认 false）由 `V20261015_01_im__notify_entitlement.sql`（`-- mse-target: platform`）种入平台库——**租户默认不可用**，平台管理员经基座「附加项管理 / 租户权益覆盖」（或套餐）按租户开通后，各业务端（OA/MES…）的渠道设置页才可勾选 im 渠道并投递——授权对整租户生效；变更秒级生效。
- **实例可用性**：`available` = `ImInstanceResolver.resolve` 探测（IM 总开关关闭 / 平台租户 / 台账停用 / 实例未配置 = 不可用），与授权是两道独立门禁（未授权/实例未就绪渠道投递跳过并记中枢投递日志）。

## 工作台与外部应用免登授权（第三方接入指南）

工作台入口两类：**mse 页面菜单**（entryType=0，候选 = 本租户已开通的子应用页面，客户端按本人菜单集做权限过滤）与**外部应用**（entryType=1，租户自配的 http(s) 直达地址）。每个入口可配置：启用/停用、图标（emoji 选择或上传图片）、打开方式（内嵌 iframe / 弹窗新窗口）、排序、**可见角色**（`role_ids` 逗号分隔角色编号；NULL/空=全员可见，非空=仅持集合内角色的用户可见，`/im/workbench/jump` 同口径复核 403 防直调绕过）；未配置任何入口时客户端回退展示全部可见页面菜单。

外部应用的**免登授权**为微信接入第三方同款的 OAuth2 授权码模式（平台是身份源，外部系统是接入方）：

```
用户点宫格
  → GET https://外部系统/app?code=<一次性授权码>          （内嵌 iframe 或弹窗，按入口配置）
  → 外部前端把 code 交给自己的后端
  → 外部后端: POST /im/oauth/token                        （服务端到服务端，免登录端点）
       { "app_key": "wb_xxx", "app_secret": "xxx", "code": "..." }
       ← { "accessToken": "...", "tokenType": "Bearer", "expiresIn": 7200,
            "user": { "tenantId": 2, "userId": 1001, "username": "zhangsan", "nickname": "张三" } }
  → 后续需要时凭 token 调 GET /im/oauth/userinfo（Authorization: Bearer <accessToken>）识别用户
```

要点：
- 凭证在「即时通讯 → 工作台配置」添加外部应用时选择**免登授权**生成（app_key 全局唯一 `wb_` 前缀；app_secret 明文仅展示一次，泄露/遗忘可「重置密钥」）。`POST /im/oauth/token` 亦兼容 `client_id/client_secret` 惯例别名与表单/查询参数提交。
- 授权码一次性（GETDEL）、默认 5 分钟有效；绑定签发者与应用，兑换时回查入口行校验（入口停用/删除或密钥重置后旧 secret 即失效）。access_token 默认 2 小时（Redis）。
- 参数调优：`mse.im.workbench.auth-code-ttl-seconds` / `access-token-ttl-seconds` / `jump-rate-limit`。
- 内嵌打开外部站点可能被对方 `X-Frame-Options/CSP` 拒绝（客户端控制台报 refused to connect），此时把入口打开方式改为**弹窗**即可。
