# im web 客户端（mse-im）

基于 `@openim/wasm-client-sdk`（版本对齐服务端 v3.8.3-patch.x）的自研聊天客户端：mse 账号登录 → `/im/client/session` 换 IM 凭证 → SDK 连接租户 OpenIM 实例；不集成官方 UI 组件库（自研 CSS 变量深浅双主题），能力全景见工程 README「聊天客户端能力」一节。

- dev：`npm install && npm run dev`（端口 4100；后端经 `MSE_API_TARGET` 指定，缺省单体 8080，微服务形态指向网关 48080）
- 构建：`npm run build`（base `/clients/web/`）；桌面版 `npm run build:desktop`（base `/` → `dist-desktop/`，供 `clients/desktop` 内置）；wasm 资产由 postinstall 自 SDK 拷贝至 `public/sdk/`（`index.html` 需引 `/sdk/wasm_exec.js`）
- 桌面端适配：`src/desktop.ts`（`window.imDesktop` 桥：服务器地址注入 + axios 经 IPC 主进程直连绕 CORS）——登录页「服务器设置」入口、设置页（满屏，含退出登录）、系统通知、未读上报全部经桥存在性守卫，浏览器直开行为不变（见 `../desktop/README.md`）
- IM 实例地址由 `/im/client/session` 下发（多实例租户无需在客户端配置），同响应的 `rtc{enabled,url}` 探测音视频通话能力
- 工作台宫格：mse 菜单入口经一次性 code 免登直达基座子页面，目标 URL 统一追加 `bare=1` 无壳直达（基座隐藏侧栏/顶栏/页签、子页面铺满，机制见根 README「微前端接入指南·无壳直达」）；外部应用入口（entryType=1）保持原样不追加
- 内嵌页主题跟随：mse 菜单入口 URL 在 `bare=1` 之外追加 `mseTheme={light|dark}&msePrimary={主色}`（IM 当前观感，主色取自 `--im-primary` 计算值），基座 bare 页瞬态应用（不写基座 localStorage，该站点常规入口主题行为不变，机制见 mse-ui `stores/theme.ts`）；IM 切深浅时向内嵌 iframe postMessage `{ type: 'mse:theme', theme, primary }` 实时跟随（基座仅 bare 模式消费、只认直接宿主 window.parent）。分享卡片深链已防御性摘除这些参数；其他宿主（移动 WebView 等）复用同一契约即可让 bare 页跟随容器主题
- 页面分享：mse 菜单入口内嵌页工具栏「分享」按钮（多选联系人/群聊/会话）——基座 bare 页把当前子应用路由 postMessage 给本客户端（`mse:bare-route`），卡片深链 = `webBase + /sub/{app}{path}?{状态query}`，经 `POST /im/client/share` 以当前用户身份代发 app-share 卡片（custom 110）；收件人点击卡片，同 webBase 源才签发一次性 code 免登新窗口直达。页面把状态写进内部路由 query 即自动随卡片带走/还原（约定见根 README「微前端接入指南」）
- 原生移动端（Android 等）接入约定（登录/会话/工作台 WebView 链路）见 `docs/mobile-client.md`；更多端（electron/ios/uniapp/flutter）复用 `src/mse.ts` + `src/im.ts` 的接线约定（登录三接口 + 过期重签）

## 结构约定

```
src/
  App.vue            # 登录页 + 三栏编排（导航/列表面板/内容区）+ 全部弹窗接线
  im.ts / mse.ts     # SDK 创建登录 / mse 后端 HTTP（session/contacts/rtc token 等）
  chat/
    MessageList.vue  # 消息列表：上滚分页、日期分隔、撤回/系统行混排、多选
    MessageItem.vue  # 气泡容器：头像/发送状态/右键菜单/已读标记/上传进度
    bubbles/         # 按 contentType 分发的气泡组件（文本(@高亮+链接)/图/语音/视频/文件/名片/链接卡片/引用/合并/位置）
    Composer.vue     # 输入区：多行 textarea + 工具栏 + 引用条 + 草稿 + @选人 + 录音
    call.ts          # 1v1 通话状态机：自研信令（custom 在线消息）+ livekit-client 房间
    media.ts         # 选图/选视频截帧/选文件/录音（MediaRecorder）→ create*MessageByFile 参数
    custom.ts        # customElem 应用层协议：link-card 链接卡片、app-share 页面分享卡片、call 通话信令
    summary.ts       # 各类型消息单行摘要（会话预览/引用条/转发摘要共用，与后端审计口径一致）
```

## 实测约定（实现时以本地 SDK d.ts 核对）

- contentType：107=合并转发、108=名片、110=custom、114=引用（勿按旧文档写成 107=引用）；
- `fileElem.sourceUrl`、`soundElem.sourceUrl/duration`、`atTextElem.text/atUserList/atUsersInfo`；
- @全员：`getAtAllTag()` 返回标记串（实测 `atAllTag`），需同时进 text 与 atUserIDList，渲染端把标记替换回「@所有人」；
- 引用：`createQuoteMessage({ text, message: JSON.stringify(被引消息) })`；
- 在线消息：`sendMessage({ ..., isOnlineOnly: true })` 不落库不进会话，接收方走 `OnRecvOnlineOnlyMessage` 事件（通话信令依赖此语义）；
- 媒体：`createXXXMessageByFile` + `sendMessage` 自动经 apiAddr `/object/*` 预签名直传 MinIO，进度走 `OnProgress` 事件。
