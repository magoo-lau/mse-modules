/**
 * 无头自检（不弹窗）：起本地静态服务器 → 隐藏窗口加载本地 web 产物 →
 * 校验 preload 桥注入、登录页渲染、桌面端服务器入口出现、/sdk 静态资源可达。
 * 用法：npm run check（CI/改动后快速回归，不触真实服务器）
 */
const { app, BrowserWindow, ipcMain } = require('electron')
const os = require('os')
const path = require('path')
const http = require('http')

const ROOT = path.join(__dirname, '..')
const webserver = require('../main/webserver')
const apiprobe = require('../main/apiprobe')

app.setPath('userData', path.join(os.tmpdir(), 'im-desktop-e2e'))

// 桩 IPC：设置页交互自检不依赖真实主进程逻辑
ipcMain.on('desktop:get-server-base', (e) => { e.returnValue = { serverBase: 'http://192.0.2.10:8080', apiMode: 'direct' } })
ipcMain.handle('setup:get', () => ({ serverBase: 'http://192.0.2.10:8080', canCancel: true }))
ipcMain.handle('setup:test', async () => ({ ok: true, status: 404 }))
ipcMain.handle('setup:save', () => ({ ok: true }))
ipcMain.handle('setup:cancel', () => {})
ipcMain.handle('setup:open', () => {})
ipcMain.handle('desktop:show-window', () => {})
ipcMain.handle('desktop:badge', () => {})
ipcMain.handle('desktop:http-fetch', () => ({ status: 0, error: 'e2e stub' }))

app.whenReady().then(async () => {
  const result = { ok: false }
  try {
    // 探测逻辑单测（mock 三种形态：直连口径 / 仅 /api 反代口径 / 非 mse 服务器）
    const json = (res) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"code":0,"data":{}}') }
    const directSrv = http.createServer((req, res) => req.url === '/auth/captcha/new' ? json(res) : (res.writeHead(404), res.end()))
    await new Promise((r) => directSrv.listen(4196, '127.0.0.1', r))
    const proxySrv = http.createServer((req, res) => req.url === '/api/auth/captcha/new' ? json(res) : (res.writeHead(404), res.end()))
    await new Promise((r) => proxySrv.listen(4198, '127.0.0.1', r))
    const hard404 = http.createServer((_req, res) => { res.writeHead(404); res.end() })
    await new Promise((r) => hard404.listen(4197, '127.0.0.1', r))
    const p = {
      direct: await apiprobe.detect('http://127.0.0.1:4196'),
      proxy: await apiprobe.detect('http://127.0.0.1:4198'),
      hard404: await apiprobe.detect('http://127.0.0.1:4197'),
    }
    directSrv.close(); proxySrv.close(); hard404.close()
    result.probe = {
      directMode: p.direct.ok && p.direct.mode === 'direct',
      proxyMode: p.proxy.ok && p.proxy.mode === 'proxy',
      hard404Rejected: !p.hard404.ok,
    }
    result.probeOk = result.probe.directMode && result.probe.proxyMode && result.probe.hard404Rejected

    const { port } = await webserver.start([path.join(ROOT, 'web-dist')], [4180, 4181, 4182, 4183, 4184, 4185])
    const { port: setupPort } = await webserver.start([path.join(ROOT, 'setup'), path.join(ROOT, 'assets')], [4186, 4187, 4188, 4189])
    result.port = port
    const win = new BrowserWindow({
      show: false,
      webPreferences: {
        preload: path.join(ROOT, 'main', 'preload.js'),
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        additionalArguments: ['--mse-im-server=' + encodeURIComponent('http://192.0.2.10:8080')],
      },
    })
    const errors = []
    win.webContents.on('console-message', (_e, level, message) => {
      if (level >= 2) errors.push(String(message).slice(0, 200))
    })
    await win.loadURL(`http://127.0.0.1:${port}/`)
    result.page = await win.webContents.executeJavaScript(`new Promise((res) => {
      const t0 = Date.now()
      const tick = () => {
        const login = !!document.querySelector('.login')
        if (login || Date.now() - t0 > 15000) {
          res({
            login,
            setupRow: !!document.querySelector('.setup-row'),               // 登录页「服务器」入口（桌面端才有）
            setupRowText: (document.querySelector('.setup-row') || {}).textContent || '',
            bridgeServer: window.imDesktop ? window.imDesktop.serverBase : null,
            sdkScriptLoaded: performance.getEntriesByType('resource').some((r) => r.name.includes('/sdk/wasm_exec.js')),
            title: document.title,
          })
        } else setTimeout(tick, 200)
      }
      tick()
    })`)
    result.consoleErrors = errors.slice(0, 5)
    result.ok = !!(result.page && result.page.login && result.page.setupRow && result.page.bridgeServer === 'http://192.0.2.10:8080' && result.page.sdkScriptLoaded) && result.probeOk
    // 同窗二次加载回归（保存服务器后原窗重载路径；「destroy 后立即 create」在渲染进程池有竞态，属禁用模式）
    try {
      await win.loadURL(`http://127.0.0.1:${port}/?reload=1`)
      result.reload = await win.webContents.executeJavaScript('!!document.querySelector(".login")')
      result.ok = result.ok && result.reload
    } catch (e) {
      result.reload = 'REJECT: ' + e.message.slice(0, 80)
      result.ok = false
    }

    // 设置窗口：主窗口存活时创建（真实「托盘/设置入口」路径）+ 预填 + 「测试连接」交互链路
    const setup = new BrowserWindow({
      show: false,
      webPreferences: { preload: path.join(ROOT, 'main', 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
    })
    await setup.loadURL(`http://127.0.0.1:${setupPort}/setup.html`)
    result.setup = await setup.webContents.executeJavaScript(`new Promise((res) => {
      const t0 = Date.now()
      const tick = () => {
        const val = document.querySelector('#server') && document.querySelector('#server').value
        if (val != null || Date.now() - t0 > 8000) {
          res({ prefilled: val, hasCancel: document.querySelector('#cancel').style.display !== 'none' })
        } else setTimeout(tick, 150)
      }
      tick()
    })`)
    await setup.webContents.executeJavaScript(`document.querySelector('#test').click()`)
    await new Promise((r) => setTimeout(r, 400))
    result.setup.testStatus = await setup.webContents.executeJavaScript(`(document.querySelector('#status') || {}).textContent || ''`)
    result.ok = result.ok && !!result.setup.prefilled && result.setup.testStatus.includes('连接成功')
    setup.destroy()
    win.destroy()
  } catch (err) {
    result.error = String((err && err.stack) || err)
  }
  // app.exit 会截断未冲刷的管道输出：先同步写完再退
  process.stdout.write(JSON.stringify(result, null, 2) + '\n', () => setTimeout(() => app.exit(result.ok ? 0 : 1), 200))
})
