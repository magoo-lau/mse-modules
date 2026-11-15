/** ai-mse 聊天桌面壳（Electron）：服务器设置 + 本地 web 加载 + 托盘常驻 + 消息提醒 */
const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, net, session, Notification } = require('electron')
const path = require('path')
const fs = require('fs')
const { loadConfig, saveConfig, normalizeServerBase, configPath } = require('./config')
const webserver = require('./webserver')
const apiprobe = require('./apiprobe')

const ROOT = path.join(__dirname, '..')
const WEB_DIST = path.join(ROOT, 'web-dist')
const SETUP_DIR = path.join(ROOT, 'setup')
const ASSETS_DIR = path.join(ROOT, 'assets')
const DEV_URL = process.env.IM_WEB_DEV || 'http://localhost:4100/'

/** 应用通知/任务栏身份（AUMID）与应用名：注册表身份（registerToastIdentity）按此登记，
 *  electron-builder 的 build.appId 需保持一致。.desktop 后缀用于跳开旧版本裸 AUMID
 *  在 Windows 通知平台的缓存记录（旧记录会让标题行一直显示原始 AUMID 串）。 */
const APP_ID = 'vip.mseapp.im.desktop'
const APP_NAME = 'ai-mse 聊天'

/** --dev：页面走 vite dev server（IM_WEB_DEV，缺省 4100）；--smoke：无窗口自检后退出（CI/安装校验）；
 *  --notify-probe：弹一条与真实消息同链路的通知后退出（看通知效果/回归通知身份） */
const isDev = process.argv.includes('--dev')
const smoke = process.argv.includes('--smoke')
const notifyProbe = process.argv.includes('--notify-probe')

let cfg = { serverBase: '', apiMode: 'proxy' }
let mainWin = null
let setupWin = null
let tray = null
let webPort = 0
let setupPort = 0
let quitting = false
let trayHinted = false
let badgeCount = 0
/** 最近一次探测结果（base → 口径）：保存时复用，未测过保存则现场探测 */
let lastDetect = { base: '', mode: '' }
/** 存活中的系统通知（Electron 通知对象可能被 GC 导致点击失效，持有引用直至 closed） */
const activeToasts = []
/** 存活中的壳内子窗口（openSubWindow 创建，closed 时移除） */
const subWins = new Set()

const appIcon = nativeImage.createFromPath(path.join(ROOT, 'assets', 'icon.png'))
const trayImg = nativeImage.createFromPath(path.join(ROOT, 'assets', 'tray.png'))
const badgeImg = nativeImage.createFromPath(path.join(ROOT, 'assets', 'badge.png'))

if (!app.requestSingleInstanceLock() && !notifyProbe) {
  // 探测模式旁路单实例锁：随真实应用并行弹通知自检（只读配置 + 弹一条通知，互不干扰）
  app.quit()
} else {
  app.setAppUserModelId(APP_ID) // Windows 通知/任务栏归属；名称与图标由 registerToastIdentity 登记
  registerIpc()

  app.whenReady().then(async () => {
    // 移除默认应用菜单（File/Edit/View/Window/Help）：聊天壳用不上，置 null 后 Alt 也不再呼出
    Menu.setApplicationMenu(null)
    registerToastIdentity()
    cfg = loadConfig()
    if (smoke) {
      console.log(JSON.stringify({
        ok: true,
        version: app.getVersion(),
        electron: process.versions.electron,
        configPath: configPath(),
        webDistReady: fs.existsSync(path.join(WEB_DIST, 'index.html')),
        serverBase: cfg.serverBase,
      }))
      app.exit(0)
      return
    }
    // 录音/通话麦克风、通知等权限对回环页面放行（浏览器版同源的默认行为在壳内需显式授予）
    session.defaultSession.setPermissionRequestHandler((_wc, permission, cb) => {
      cb(['notifications', 'media', 'clipboard-sanitized-access', 'fullscreen', 'pointerLock'].includes(permission))
    })
    // 壳内下载统一落系统「下载」目录（Electron 默认行为），完成后系统通知反馈（此前静默落盘无感知）；
    // 子窗直链下载（文件消息兜底/附件直链）由隐形子窗发起，落定后关掉不留白板窗
    session.defaultSession.on('will-download', (_e, item) => {
      const url = item.getURL()
      item.once('done', (_ev, state) => {
        for (const w of subWins) {
          if (!w.isDestroyed() && !w.isVisible() && w._mseUrl === url) w.close()
        }
        if (state === 'completed') void showMessageNotification({ title: '下载完成', body: item.getFilename() })
        else if (state === 'interrupted') void showMessageNotification({ title: '下载失败', body: item.getFilename() })
      })
    })
    // --notify-probe：弹一条与真实消息同链路的通知后退出（验证注册表身份/图标/头像兜底，不进窗口流程）
    if (notifyProbe) {
      void showMessageNotification({ title: '通知自检（法师群）', body: '这是一条测试消息：标题行=应用名+图标，左侧=会话头像', tag: 'probe' })
      setTimeout(() => app.exit(0), 8000)
      return
    }
    const web = await webserver.start([WEB_DIST], [4180, 4181, 4182, 4183, 4184, 4185])
    webPort = web.port
    // 设置页独立回环源（不与 web 产物同源；窗口间 file:// 与 http 混装在部分环境不稳，统一走回环 http）
    const setup = await webserver.start([SETUP_DIR, ASSETS_DIR], [4186, 4187, 4188, 4189])
    setupPort = setup.port
    createTray()
    if (cfg.serverBase) showMainWindow()
    else showSetupWindow()
  })

  app.on('second-instance', () => { cfg.serverBase ? showMainWindow() : showSetupWindow() })
  app.on('before-quit', () => { quitting = true })
  // 托盘常驻：窗口全关也不退出（退出走托盘菜单）
  app.on('window-all-closed', () => { /* keep running */ })
}

// -------------------- 通知身份（Windows 注册表 AUMID 登记） --------------------

/** 新消息系统通知（desktop:notify IPC 与 --notify-probe 共用）：
 *  左侧大图 = 会话头像（群→群头像 / 单聊→对方头像，页面已绝对化），拉取失败兜底应用 logo；
 *  标题行名称与图标来自注册表身份（registerToastIdentity）。tag=会话 id，同会话覆盖合并。 */
async function showMessageNotification(p) {
  if (!Notification.isSupported()) return { ok: false }
  const text = (v, cap) => String(v ?? '').replace(/\s+/g, ' ').trim().slice(0, cap)
  const title = text(p?.title, 40) || '新消息'
  const body = text(p?.body, 120)
  const tag = p?.tag ? text(p.tag, 80) : undefined
  const convId = p?.convId ? text(p.convId, 80) : ''
  let icon = appIcon
  if (/^https?:\/\//i.test(p?.avatar || '')) {
    // 头像竞速 1.2s：正常几百毫秒内返回；拉不到/超时立即以应用 logo 弹出，不让提醒等头像
    const img = await Promise.race([fetchAvatar(p.avatar), new Promise((r) => setTimeout(() => r(null), 1200))])
    if (img && !img.isEmpty()) icon = img
  } else if (/^data:image\//i.test(String(p?.avatarData || ''))) {
    // 页面 canvas 画的字头像 PNG dataURL（群/人无头像图时，与界面字头像同款）
    try {
      const img = nativeImage.createFromDataURL(p.avatarData)
      if (!img.isEmpty()) icon = img
    } catch { /* 解析失败兜底应用 logo */ }
  }
  const n = new Notification({ title, body, icon, ...(tag ? { tag } : {}) })
  activeToasts.push(n)
  n.on('click', () => {
    showMainWindow()
    if (convId && mainWin && !mainWin.isDestroyed()) mainWin.webContents.send('desktop:notify-click', convId)
  })
  n.on('closed', () => {
    const i = activeToasts.indexOf(n)
    if (i >= 0) activeToasts.splice(i, 1)
  })
  n.show()
  return { ok: true }
}

/** 拉取会话头像为通知图标（失败返回 null，调用方兜底应用 logo） */
async function fetchAvatar(url) {
  try {
    const r = await net.fetch(url, { signal: AbortSignal.timeout(5000) })
    if (!r.ok) return null
    return nativeImage.createFromBuffer(Buffer.from(await r.arrayBuffer()))
  } catch { return null }
}

/** 未装 StartMenu 快捷方式时（开发态 / 便携包），Windows 通知标题行解析不到应用名，
 *  会把 AUMID 原始串（如 vip.mseapp.im）当名字裸显。按官方机制写 HKCU 注册表身份
 *  （DisplayName + IconUri），标题行即显示应用名与应用图标，先于任何 toast 生效、幂等可重写。
 *  图标复制到 userData 再登记：便携包的解压目录每次可能变化，userData 路径稳定。
 *  注册失败只影响标题行显示（退化为裸 AUMID），不阻断启动。 */
function registerToastIdentity() {
  if (process.platform !== 'win32') return
  try {
    let iconUri = path.join(ASSETS_DIR, 'icon.png')
    try {
      const copied = path.join(app.getPath('userData'), 'notify-icon.png')
      fs.copyFileSync(iconUri, copied)
      iconUri = copied
    } catch { /* 复制失败退回资源目录原图 */ }
    const key = 'HKCU\\SOFTWARE\\Classes\\AppUserModelId\\' + APP_ID
    const reg = (args) => require('child_process').execFileSync('reg', args, { stdio: 'ignore' })
    reg(['add', key, '/v', 'DisplayName', '/t', 'REG_EXPAND_SZ', '/d', APP_NAME, '/f'])
    reg(['add', key, '/v', 'IconUri', '/t', 'REG_EXPAND_SZ', '/d', iconUri, '/f'])
    // 旧版本 AUMID 的身份键清理（未注册过则无此键，忽略失败）
    try { reg(['delete', 'HKCU\\SOFTWARE\\Classes\\AppUserModelId\\vip.mseapp.im', '/f']) } catch { /* 忽略 */ }
  } catch { /* 忽略 */ }
}

// -------------------- 主窗口（加载本地 web 产物） --------------------

function showMainWindow() {
  if (!cfg.serverBase && !isDev) return showSetupWindow()
  if (setupWin && !setupWin.isDestroyed()) setupWin.hide()
  if (mainWin && !mainWin.isDestroyed()) {
    badgeCount = 0
    updateBadge()
    mainWin.show()
    mainWin.focus()
    return
  }
  mainWin = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 980,
    minHeight: 640,
    show: false,
    backgroundColor: '#f5f6f8',
    icon: appIcon,
    title: APP_NAME,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
      additionalArguments: [
        '--mse-im-server=' + encodeURIComponent(cfg.serverBase || ''),
        ...(isDev ? ['--mse-im-dev'] : []),
      ],
    },
  })
  if (isDev) mainWin.loadURL(DEV_URL)
  else mainWin.loadURL(`http://127.0.0.1:${webPort}/`)
  mainWin.once('ready-to-show', () => mainWin.show())
  // 关闭 = 最小化到托盘（微信式常驻；真正退出走托盘菜单）
  mainWin.on('close', (e) => {
    if (quitting) return
    e.preventDefault()
    hideToTray()
  })
  mainWin.on('focus', () => {
    badgeCount = 0
    updateBadge()
  })
  // 新开页面（链接卡片 / 工作台「新窗口打开」/外链）：一律壳内子窗口加载（共用会话），不再交系统浏览器
  mainWin.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) openSubWindow(url)
    return { action: 'deny' }
  })
}

function hideToTray() {
  if (!mainWin || mainWin.isDestroyed()) return
  mainWin.hide()
  updateBadge()
  if (!trayHinted && process.platform === 'win32' && tray) {
    tray.displayBalloon({ icon: trayImg, title: APP_NAME, content: '已最小化到系统托盘，点击图标恢复窗口' })
    trayHinted = true
  }
}

/** 未读角标：窗口隐藏时任务栏叠标 + 闪烁 + 托盘 tooltip 计数；聚焦/显示即清除 */
function updateBadge() {
  const active = !!(mainWin && !mainWin.isDestroyed() && !mainWin.isVisible() && badgeCount > 0)
  if (mainWin && !mainWin.isDestroyed()) {
    mainWin.setOverlayIcon(active ? badgeImg : null, active ? `${badgeCount} 条未读消息` : '')
    mainWin.flashFrame(active)
  }
  if (tray) tray.setToolTip(`${APP_NAME}${badgeCount > 0 ? `（${badgeCount} 条未读）` : ''}`)
}

// -------------------- 壳内子窗口（链接卡片 / 工作台页面 / 外链） --------------------

/** 壳内打开 http(s) 页面（分享卡片/链接卡片/工作台「新窗口打开」/外链）：不设 partition 即共用
 *  defaultSession，分享卡片的一次性免登 code 首开落地后会话跨窗口、跨重启共享。刻意不带
 *  preload——打开的可能是任意外部页面，imDesktop 桥（httpFetch 可发已认证请求）不得暴露给它们。
 *  同源同路径已开窗则聚焦复用（分享卡片每次点击都重新签发 code，query 变化不判重），避免连点多窗。 */
function openSubWindow(url) {
  let key = ''
  try { const u = new URL(url); key = u.origin + u.pathname } catch { return }
  for (const w of subWins) {
    if (w.isDestroyed()) { subWins.delete(w); continue }
    try {
      const u = new URL(w.webContents.getURL())
      if (u.origin + u.pathname === key) { w.show(); w.focus(); return }
    } catch { /* 尚未完成加载取不到址，视为不复用 */ }
  }
  const win = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 980,
    minHeight: 640,
    show: false,
    backgroundColor: '#f5f6f8',
    icon: appIcon,
    title: APP_NAME,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  })
  win._mseUrl = url
  subWins.add(win)
  win.once('ready-to-show', () => win.show())
  win.on('closed', () => subWins.delete(win))
  // 加载失败（断网/地址无效）也要出窗：既给用户感知，也避免隐形窗一直占着复用键
  win.webContents.on('did-fail-load', () => { if (!win.isDestroyed() && !win.isVisible()) win.show() })
  // 页面内再开新窗（工作台跳子应用等）递归壳内处理
  win.webContents.setWindowOpenHandler(({ url: next }) => {
    if (/^https?:/i.test(next)) openSubWindow(next)
    return { action: 'deny' }
  })
  win.loadURL(url).catch(() => { /* 加载被中断（用户即刻关窗等）忽略 */ })
}

// -------------------- 服务器设置窗口 --------------------

function showSetupWindow() {
  if (mainWin && !mainWin.isDestroyed() && mainWin.isVisible()) mainWin.hide()
  if (setupWin && !setupWin.isDestroyed()) {
    // 复用窗口需重载页面：取消按钮显隐/状态行随配置变化，不复用首次加载的残留状态
    setupWin.webContents.reload()
    setupWin.show()
    setupWin.focus()
    return
  }
  setupWin = new BrowserWindow({
    width: 480,
    height: 420,
    resizable: false,
    maximizable: false,
    minimizable: false,
    fullscreenable: false,
    show: false,
    backgroundColor: '#f2f3f5',
    icon: appIcon,
    title: '服务器设置',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  })
  setupWin.loadURL(`http://127.0.0.1:${setupPort}/setup.html`)
  setupWin.once('ready-to-show', () => setupWin.show())
  setupWin.on('close', (e) => {
    if (quitting) return
    e.preventDefault()
    if (cfg.serverBase) {
      setupWin.hide()
      showMainWindow()
    } else {
      app.quit() // 首次未配置服务器：无处可去，关闭即退出
    }
  })
}

/** 保存服务器地址（base 已探测出接口口径 mode）：落盘 → 主窗口原窗重载（页面全量重置，换站时先清 indexdb/localStorage 残留）。
 *  服务端地址/口径经 IPC 实时下发，重载后 preload 取到新值。
 *  注意：这里刻意不销毁重建窗口——Chromium 渲染进程池在「destroy 后立即 create」时存在
 *  回收竞态，新窗口加载会随机 ERR_FAILED（实测 100% 复现），原地 loadURL 无此问题。 */
async function applyServerBase(base, mode) {
  const changed = base !== cfg.serverBase || mode !== cfg.apiMode
  cfg = { serverBase: base, apiMode: mode }
  try { saveConfig(cfg) } catch { /* 写失败仍可本次会话使用 */ }
  if (setupWin && !setupWin.isDestroyed()) setupWin.hide()
  if (!mainWin || mainWin.isDestroyed()) {
    showMainWindow()
    return { ok: true }
  }
  if (changed) {
    try {
      await session.defaultSession.clearStorageData({ storages: ['indexdb', 'localstorage'] })
    } catch { /* 忽略 */ }
  }
  badgeCount = 0
  updateBadge()
  mainWin.show()
  try {
    await mainWin.loadURL(isDev ? DEV_URL : `http://127.0.0.1:${webPort}/`)
  } catch { /* 重载中断（用户即刻关窗等）忽略 */ }
  return { ok: true }
}

// -------------------- 托盘 --------------------

function createTray() {
  tray = new Tray(trayImg)
  tray.setToolTip(APP_NAME)
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开主界面', click: () => showMainWindow() },
    { label: '服务器设置…', click: () => showSetupWindow() },
    { type: 'separator' },
    { label: '退出', click: () => { quitting = true; app.quit() } },
  ]))
  tray.setIgnoreDoubleClicks?.(true) // macOS 专属，Windows 无此 API（可选调用兜底）
  tray.on('click', () => showMainWindow())
}

// -------------------- IPC（preload 桥一一对应） --------------------

function registerIpc() {
  // preload 同步取当前服务器配置（地址 + 接口口径；保存后原窗重载即用新值）
  ipcMain.on('desktop:get-server-base', (e) => { e.returnValue = cfg })

  ipcMain.handle('setup:get', () => ({ serverBase: cfg.serverBase, canCancel: !!cfg.serverBase }))

  ipcMain.handle('setup:test', async (_e, input) => {
    const base = normalizeServerBase(input)
    if (!base) return { ok: false, error: '地址无效：需为 http(s) 服务器地址，如 http://192.168.1.10:8080' }
    const r = await apiprobe.detect(base)
    if (r.ok) lastDetect = { base, mode: r.mode }
    return r
  })

  ipcMain.handle('setup:save', async (_e, input) => {
    const base = normalizeServerBase(input)
    if (!base) return { ok: false, error: '地址无效：需为 http(s) 服务器地址，如 http://192.168.1.10:8080' }
    // 口径：复用本地址最近的探测结果；没测过就现场探测（保存即校验，避免存进打不通的地址）
    let mode = lastDetect.base === base ? lastDetect.mode : ''
    if (!mode) {
      const r = await apiprobe.detect(base)
      if (!r.ok) return r
      mode = r.mode
    }
    return await applyServerBase(base, mode)
  })

  ipcMain.handle('setup:cancel', () => {
    if (setupWin && !setupWin.isDestroyed()) setupWin.hide()
    showMainWindow()
  })

  ipcMain.handle('setup:open', () => showSetupWindow())
  ipcMain.handle('desktop:show-window', () => showMainWindow())

  ipcMain.handle('desktop:badge', (_e, n) => {
    badgeCount = Math.max(0, Number(n) || 0)
    updateBadge()
  })

  /** 新消息系统通知入口（web 侧只做「该不该提醒」判定，样式与头像在主进程统一出） */
  ipcMain.handle('desktop:notify', (_e, p) => showMessageNotification(p))

  /** axios IPC 转发：主进程 net 直连服务端（绕开回环页面源的 CORS 约束） */
  ipcMain.handle('desktop:http-fetch', async (_e, req) => {
    const url = typeof req?.url === 'string' ? req.url : ''
    if (!/^https?:\/\//i.test(url)) return { status: 0, error: '仅支持 http(s) 地址' }
    const timeout = Math.min(Math.max(Number(req.timeout) || 15000, 1000), 120000)
    try {
      const init = {
        method: req.method || 'GET',
        headers: req.headers || {},
        signal: AbortSignal.timeout(timeout),
      }
      if (req.bodyB64) init.body = Buffer.from(req.bodyB64, 'base64')
      else if (typeof req.body === 'string') init.body = req.body
      const r = await net.fetch(url, init)
      const headers = {}
      r.headers.forEach((v, k) => { headers[k] = v })
      return { status: r.status, statusText: r.statusText, headers, body: await r.text() }
    } catch (err) {
      const reason = err?.cause?.code || (err?.name === 'TimeoutError' ? '请求超时' : '') || err?.message || '网络错误'
      return { status: 0, error: '无法连接服务器：' + reason }
    }
  })
}
