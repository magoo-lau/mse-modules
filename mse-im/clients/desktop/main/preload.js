/**
 * 预加载桥：主窗口页面暴露 window.imDesktop（web 客户端 src/desktop.ts 消费），
 * 服务器设置页暴露 window.imSetup（setup/setup.html 消费）。
 * 服务端地址经 IPC 同步取当前值（保存后主窗口原窗重载即生效，不经 argv 旧值）。
 */
const { contextBridge, ipcRenderer } = require('electron')

const argv = (process.argv || [])
const argValue = (name) => {
  const hit = argv.find((a) => a === name || a.startsWith(name + '='))
  if (!hit) return undefined
  return hit.includes('=') ? hit.split('=').slice(1).join('=') : ''
}

const serverCfg = (() => {
  // 主链路：主进程实时配置（sendSync 在 preload 阶段同步返回，页面请求零等待）
  try {
    const v = ipcRenderer.sendSync('desktop:get-server-base')
    if (v && typeof v === 'object') return { serverBase: v.serverBase || '', apiMode: v.apiMode === 'direct' ? 'direct' : 'proxy' }
    if (typeof v === 'string') return { serverBase: v, apiMode: 'proxy' }
  } catch { /* 无处理器时（自检桩）回落 argv */ }
  const raw = argValue('--mse-im-server')
  try { return { serverBase: raw != null ? decodeURIComponent(raw) : '', apiMode: 'proxy' } } catch { return { serverBase: raw || '', apiMode: 'proxy' } }
})()

contextBridge.exposeInMainWorld('imDesktop', {
  serverBase: serverCfg.serverBase,
  apiMode: serverCfg.apiMode,
  dev: argv.includes('--mse-im-dev'),
  openServerSetup: () => ipcRenderer.invoke('setup:open'),
  showWindow: () => ipcRenderer.invoke('desktop:show-window'),
  badge: (count) => ipcRenderer.invoke('desktop:badge', count),
  httpFetch: (req) => ipcRenderer.invoke('desktop:http-fetch', req),
  /** 新消息系统通知（主进程统一出样式：标题行应用名+图标，左侧会话头像，同会话合并） */
  notify: (payload) => ipcRenderer.invoke('desktop:notify', payload),
  /** 通知点击：主进程唤回主窗口后回传会话 id，页面负责直达该会话 */
  onNotifyClick: (cb) => ipcRenderer.on('desktop:notify-click', (_e, convId) => cb(convId)),
})

contextBridge.exposeInMainWorld('imSetup', {
  get: () => ipcRenderer.invoke('setup:get'),
  test: (url) => ipcRenderer.invoke('setup:test', url),
  save: (url) => ipcRenderer.invoke('setup:save', url),
  cancel: () => ipcRenderer.invoke('setup:cancel'),
})
