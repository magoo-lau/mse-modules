# IM 原生移动端接入约定（Android / Kotlin 先行）

> 面向 IM 原生移动端 App（Android Kotlin 等）的接入约定：登录、IM 会话、工作台跳转（WebView）三条链路全部复用现有接口，**后端零改动**。设计依据 [docs/tech/openim-integration.md](../../../docs/tech/openim-integration.md)；web 客户端（`clients/web/`）的 `src/mse.ts` + `src/im.ts` 是接线参考实现。

## 总体形态

```
原生 App（Kotlin）
├── 聊天/通话/通讯录：OpenIM Android SDK 直连租户 IM 实例（apiAddr/wsAddr 由 session 下发，不经 mse 网关）
├── 业务接口：HTTPS 直调 mse 网关（/auth、/im/**），Authorization 头携 Sa-Token 裸 token
└── 工作台业务页：WebView 打开基座子页面（?ssoCode 免登 + ?bare=1 无壳），移动版 H5 与 PC 同源同权限
```

不建独立移动站点：移动版 H5 页面仍挂在基座子应用体系内，鉴权/主题/权限链路与 PC 完全一致。

## 一、登录链路（复用基座登录）

| 步骤 | 接口 | 说明 |
|---|---|---|
| 1 | `POST /auth/login` | 请求体 `{tenantCode, username, password, originDomain, device: 'app'}`；验证码/2FA/域名绑定校验全部继承。`device` 缺省 `pc`，App 必须显式传 `app` |
| 2 | 保存 token | 响应 `LoginResult{tokenValue, tenantId, userId, nickname}`，存 App 安全存储（Keystore 加密，勿落明文 SharedPreferences） |
| 3 | 携带 | 后续请求头 `Authorization: <tokenValue>`（Sa-Token 裸值，无 Bearer 前缀） |

要点：

- **无 refresh token**：token 有效期 30 天（网关/服务端统一 Sa-Token 配置），过期后调 `POST /auth/logout` 清理并引导重登；客户端应以 401 响应统一触发该流程。
- **多端策略**：Sa-Token `is-concurrent=true / is-share=false`（多端各成独立会话，可在基座「登录设备」页自助下线）；租户级单点/同端互斥策略（`sys.login.multiDevice` / `sys.login.sameDeviceExclusive`）沿用，App 传 `device: 'app'` 才能被同端互斥正确区分。
- **token 有效期内可主动续签 IM 会话**（见下节），但 mse 登录态本身无静默续期机制，App 需自行处理过期重登。

## 二、IM 会话（platformID=2）

| 步骤 | 接口 | 说明 |
|---|---|---|
| 1 | `POST /im/client/session` | 请求体 `{platformID: 2}`（OpenIM 官方端标识：1 iOS / 2 Android / 3 Windows / 4 OSX / 5 Web / 6 小程序 / 7 Linux）；需登录态，经网关校验 |
| 2 | SDK 登录 | 响应 `{userID, imToken, apiAddr, wsAddr, expireAt, nickname, faceURL, rtc}` → OpenIM Android SDK `login(userID, imToken, apiAddr, wsAddr, platformID=2)` |
| 3 | 过期重签 | 监听 `OnUserTokenExpired/Invalid/WillExpire` → 重调 session → `login`（web 客户端 `App.vue` 同款范式；Web 用 platformID=5 与 Android 会话互不顶号） |

音视频通话：`rtc{enabled, url}` 探测能力开关，`enabled=false` 隐藏发起入口；join 房间前 `POST /im/client/rtc/token` 取 LiveKit JWT（信令协议见 `chat/custom.ts` 与根文档 6.5 节，Android 侧自实现同款六态信令即可互通）。

## 三、工作台跳转（WebView + 一次性 code 免登 + 无壳）

App 不自行拼接基座 URL 登录，**点击宫格时实时签发一次性 code**（60s 有效、单次可用，勿预签批量跳转链接）：

| 步骤 | 接口 | 说明 |
|---|---|---|
| 1 | `GET /im/workbench` | 宫格配置 `{webBaseUrl, items[]}`（租户配置 > 全局缺省；items 为空则按 `/system/profile/me` 菜单全量展示，与 web 客户端 `loadWorkbench()` 同口径） |
| 2 | `POST /im/sso/code` | 请求体 `{targetUrl: webBaseUrl + path + '?bare=1'}`（path 已带 query 时改 `&`），响应 `data.url` 为带 `?ssoCode=` 的完整地址 |
| 3 | WebView 打开 | 加载 `data.url`；基座路由守卫自动兑换 code 换标准租户会话、摘参后进入目标子页面 |

要点：

- **`?bare=1` 无壳直达**：基座隐藏侧栏/顶栏/页签，子页面铺满 WebView 视口（机制见根 README「微前端接入指南·无壳直达」）。WebView 内看到的就是纯业务页，无基座菜单外壳。
- **免登天然成立**：一次性 code 绑定的是 `targetUrl` 的 Origin（非签发者），App 签发、WebView 内基座页面兑换时 Origin 均为 `webBaseUrl`，同源匹配通过；`/im/sso/exchange` 在网关白名单内，无需 App 侧 token。WebView 建议开启第三方 cookie/同源存储以保住兑换出的会话。
- **WebView UA 约定**：App WebView 追加 UA 标识 `MseApp/Android`（如 `... Chrome/xxx MseApp/Android`），供移动版 H5 页面按场景适配（区分 App WebView 与手机浏览器）；PC 浏览器不带该标识不受影响。
- **code 安全**：一次性（Redis GETDEL）+ 60s 时效 + 同用户限频；WebView 加载失败（超时/断网）时 code 已作废，重试必须重新签发。

## 四、移动版 H5 承载约定（各业务子应用）

工作台宫格 v1 的 path 配置各端共用（同一入口，不区分 PC/移动），页面自行按视口/UA 呈现：

1. **移动页面挂子应用体系内**：各子应用按需做移动路由（如 `/m/**` 前缀的移动版页面）或响应式布局，登记为普通 type=3 页面菜单（或不出菜单、仅按 path 直达——无菜单路由经 `/sub/{name}/**` 通配仍可打开，权限由后端接口收敛）；**不建独立移动前端工程**。
2. **打开链路统一**：`?ssoCode=xxx&bare=1` 免登 + 无壳；鉴权（Authorization 头）、主题（`html.dark` / `--el-*` 变量，跟随 `mse-theme` 本地偏好）、按钮权限（wujie props `permissions`）与 PC 完全一致。
3. **场景识别**：`navigator.userAgent` 含 `MseApp/` 即 App WebView，可进一步裁剪（隐藏"返回工作台"类桌面导航、启用大触控热区）；纯移动浏览器访问时页面自行兜底提示建议使用 App。
4. **后续扩展**：若确需按端区分宫格入口（PC 与移动不同 path 集合），再扩展 `im_workbench_item` 的端别维度字段（当前 v1 不做，避免过度设计）。

## 五、已知限制（App 侧需知）

| 限制 | 说明 | 应对 |
|---|---|---|
| 2FA 账号 | `/auth/login` 返回 `twoFactorRequired=true` 时暂无 App 两段式实现（web 客户端同款直接拒绝） | 提示"请在 Web 端登录"；后续按 `/auth/2fa/challenge` + `/auth/login/2fa` 补两段式 |
| 短信登录 | 后端无短信验证码登录通道（仅密码 + 邮箱码/TOTP） | 暂无 |
| 图形验证码 | 登录风控 risk 模式触发 `captcha` 要求时需 App 自绘验证码 UI（`GET /auth/captcha/new` 预检） | 实现验证码输入弹层 |
| CORS | 原生 App 不受 CORS 约束；WebView 内基座 H5 与 webBaseUrl 同域反代部署（OpenIM 开通编排 nginx 段同款）即天然满足 | 部署期确认同域 |
| token 30 天 | 无 refresh token，过期需重登 | 401 统一拦截 → 重登引导 |

## 六、待办衔接

`TODO.md`「更多端 demo」待办以本文档为接入依据：Android Kotlin 最小闭环 = 登录 + session + OpenIM SDK 收发 + 工作台 WebView（链路 1-3 节），SDK 初始化层参照 web 端 `src/im.ts` 封装。
