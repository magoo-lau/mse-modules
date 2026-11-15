# im 桌面客户端（mse-im · Electron 壳）

把 `clients/web` 聊天客户端包成微信式桌面应用：**界面产物内置在应用里（本地加载，不经服务器）**、首启/随时可配置服务器地址、托盘常驻、原生消息提醒。壳只做宿主（`clients/desktop/` 独立 npm 工程），聊天 UI 与能力全部复用 `clients/web`（桌面差异项在 web 内经 `window.imDesktop` 桥守卫，浏览器版零感知）。

## 功能

| 功能 | 行为 |
| --- | --- |
| 服务器设置 | 首次启动弹「服务器设置」窗口；登录页「修改…」与主界面设置页「修改服务器地址…」随时可回设置界面；地址 + 接口口径（direct/proxy 自动识别）落 `userData/im-desktop-config.json`；连接测试打真实业务接口（非 mse 服务会明确拒绝）；保存后主窗口原窗重载、换站自动清登录存档与本地缓存 |
| 本地 web | 界面 = `clients/web` 的桌面构建产物（`npm run build:desktop` → `dist-desktop/` → 同步为壳内 `web-dist/`），经 `127.0.0.1` 回环静态服务器加载（仅本机可访问）。服务器只提供**数据接口**，页面/JS/wasm 全部本地 |
| 托盘常驻 | 关闭窗口 = 最小化到托盘（首缩提示气泡）；托盘菜单：打开主界面 / 服务器设置… / 退出；点击托盘图标恢复窗口 |
| 消息提醒 | 新消息系统通知（Windows toast；离线同步不轰炸、当前会话在前台不打扰、免打扰会话跳过、同会话合并覆盖）；通知由主进程统一出样式：标题行 = 应用名 + 应用图标（注册表 AUMID 身份），左侧大图 = 会话头像（群→群头像 / 单聊→对方头像），无头像图时页面 canvas 画与界面同款的字头像（蓝底白字首字符、机器人绿底🤖），两者皆缺兜底应用 logo；点击通知回主窗口并直达会话；窗口隐藏时任务栏叠标 + 闪烁 + 托盘 tooltip 未读计数；主界面设置页（满屏）可关通知 |
| 桌面细节 | 链接卡片/工作台「新窗口打开」/外链一律壳内子窗口打开（共用会话，下载落系统「下载」目录并通知）；麦克风/摄像头/通知权限对回环页面放行（录音/通话可用）；单实例锁（重复启动唤起已有窗口）；无应用菜单栏（`Menu.setApplicationMenu(null)`，File/Edit/View 等默认菜单彻底移除，Alt 不呼出） |

## 构建与运行

```bash
# 0) 前置：web 客户端依赖已装（clients/web node_modules 就绪）
cd clients/web && npm run build:desktop     # 桌面构建（--base=/ → dist-desktop/）
cd ../desktop && npm install                # 首次（.npmrc 已配 npmmirror 的 electron 镜像）

npm start          # 同步 web-dist 后启动应用
npm run check      # 无头自检：本地加载 + 桥注入 + 登录页/设置页交互（CI/改动后回归）
npm run smoke      # 最小自检：主进程可启动、产物/配置路径就绪
npm run notify-test # 弹一条与真实消息同链路的通知后退出（看通知效果/回归通知身份，可与运行中的应用并存）
npm run dev        # 开发：页面走 vite dev server（先在 clients/web npm run dev，IM_WEB_DEV 可改地址）
npm run icons      # 重新生成应用图标（改 scripts/gen-icons.mjs 后）
npm run dist       # 打 Windows 安装包/便携包（electron-builder → release/，需先 build:desktop）
```

## 服务器地址口径

两种部署口径**都支持，测试连接自动识别**（`main/apiprobe.js` 探测 `/auth/captcha/new`——登录链路在用的免登录接口，应答 `{code:0}` 才算 mse 服务在位）：

- **direct**（推荐，桌面端最常见）：填后端根地址，接口在根路径——单体 `http://host:8080`、网关 `http://host:48080`；
- **proxy**：填部署站点根地址（nginx 把 `/api/*` 转发后端并去前缀，web 客户端部署同款约定）。

识别结果随地址一起落盘（`apiMode`），页面侧 axios 基址与站内素材地址按口径拼接（`src/desktop.ts` apiPrefix）。**保存即校验**：没测过就现场探测，两口径都未命中 mse 接口时拒绝保存并提示「地址可达，但未找到 mse 认证接口」。IM 消息服务器（apiAddr/wsAddr）由 `/im/client/session` 下发，无需在此填写。地址可带路径后缀适配带前缀的反代。

跨域说明：壳内页面源是回环地址，axios 请求经 IPC 交主进程 `net` 直连服务端（无 CORS 概念）；OpenIM apiAddr（REST/WS）与 MinIO 直传仍由页面直连，依赖 OpenIM/MinIO 默认的全域放行（官方默认行为）。

## 结构

```
clients/desktop/
├── main/
│   ├── main.js        # 生命周期/窗口/托盘/IPC/HTTP 转发/系统通知（applyServerBase 刻意原窗重载，见文件内注释）
│   ├── apiprobe.js    # mse 服务探测：直连//api 反代口径自动识别 + 非 mse 服务拒绝
│   ├── preload.js     # contextBridge：主窗口 window.imDesktop / 设置页 window.imSetup
│   ├── config.js      # userData 配置存取 + 地址归一化
│   └── webserver.js   # 回环静态服务器（主界面 4180+/设置页 4186+，多根目录查找）
├── setup/setup.html   # 服务器设置窗口（自包含单页）
├── assets/            # 图标（scripts/gen-icons.mjs 纯 Node 生成，零依赖）
├── scripts/
│   ├── sync-web.js    # clients/web/dist-desktop → web-dist
│   ├── gen-icons.mjs  # 图标生成（SDF 绘制 + 盒式降采样）
│   └── e2e-check.js   # 无头自检（桩 IPC；登录页/设置页/同窗重载回归）
└── web-dist/          # gitignore：由 sync-web.js 生成
```

web 侧桌面适配（`clients/web/src/`）：`desktop.ts`（桥接 + IPC HTTP 适配器 + 服务器地址注入）、`mse.ts`（baseURL/存档按站校验/originDomain）、`chat/util.ts`（assetUrl 绝对化）、`App.vue`（登录页服务器入口/满屏设置页（含退出登录）/系统通知/未读上报）。全部经 `window.imDesktop` 存在性守卫，浏览器版行为不变。

## 已知约定与限制

- Windows 通知标题行（应用名 + 图标）来自启动时幂等写入的注册表身份（`HKCU\Software\Classes\AppUserModelId\<AUMID>`，`main.js` registerToastIdentity）；开发态/便携包（无安装快捷方式）与安装版同样生效。AUMID 统一为 `vip.mseapp.im.desktop`（与 electron-builder `build.appId` 一致；`.desktop` 后缀用于跳开旧版本裸 AUMID 在通知平台的缓存记录——曾以旧 AUMID 弹过通知的机器，标题行会一直缓存显示原始 AUMID 串，新 AUMID 首启即正确）。
- 换服务器保存即清该站登录存档与本地缓存（含 OpenIM 消息缓存），需重新登录——与「换服务器=换世界」语义一致。
- 壳内窗口一律回环 http 加载（不用 `file://` / 自定义协议）：getUserMedia（录音/通话）要求可信源，https 源连 OpenIM 的 `ws://` 又会被 Mixed Content 拦截，回环 http 两者兼得。
- 链接卡片/工作台「新窗口打开」/外链在壳内子窗口打开（`openSubWindow`）：不设 partition 共用 defaultSession——分享卡片一次性免登 code 首开落地后会话跨窗口、跨重启共享；同源同路径已开窗聚焦复用（免登 code 每次点击都变，不判重 query）；子窗口刻意不带 preload，`imDesktop` 桥（`httpFetch` 可发已认证请求）不暴露给被打开页面；下载统一落系统「下载」目录（`will-download`），完成后系统通知反馈，隐形直链子窗落定即关。
- **禁用模式**：销毁窗口后立即创建新窗口（渲染进程池回收竞态，实测必现 `ERR_FAILED`）——换服务器/改配置一律原窗 `loadURL` 重载（`applyServerBase` 有注释）。
