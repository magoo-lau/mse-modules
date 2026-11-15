/**
 * 桌面端（Electron 壳）桥接适配：服务端地址注入 + 窗口协作 + HTTP 转发。
 * 浏览器直开时 window.imDesktop 不存在，全部能力自动退化为 web 版原行为（同源 /api 相对路径）。
 * 桥由 clients/desktop/main/preload.js 注入，字段与 src/mse.ts、App.vue 的桌面协作一一对应。
 */
import { AxiosError } from 'axios'
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

/** Electron preload 注入的桥接口 */
interface ImDesktopBridge {
  /** 配置的服务端根地址（--mse-im-dev 开发模式时为空串，接口走 vite 代理） */
  serverBase: string
  /** 接口口径：direct=后端根地址直连（接口在根路径）/ proxy=站点根 + /api 反代（web 部署同款） */
  apiMode: 'direct' | 'proxy'
  /** 开发模式：页面由 vite dev server 提供 */
  dev: boolean
  /** 打开服务器设置窗口（登录页/设置面板共用入口） */
  openServerSetup(): void
  /** 显示并聚焦主窗口（通知点击等场景） */
  showWindow(): void
  /** 上报未读数：窗口隐藏时托盘 tooltip + 任务栏叠标 + 闪烁 */
  badge(count: number): void
  /** HTTP 转发：主进程 net 直连服务端（绕开页面源 CORS 约束） */
  httpFetch(req: DesktopHttpReq): Promise<DesktopHttpResp>
  /** 新消息系统通知（主进程统一出样式：标题行应用名+图标，左侧会话头像，同会话按 tag 合并） */
  notify(payload: DesktopNotifyPayload): Promise<unknown>
  /** 通知点击回跳：主进程唤回主窗口后回传会话 id */
  onNotifyClick(cb: (convId: string) => void): void
}

/** 新消息通知载荷（桌面壳 desktop:notify IPC；convId 用于点击直达会话） */
export interface DesktopNotifyPayload {
  title: string
  body: string
  /** 会话 id：同会话通知覆盖合并 + 点击回跳定位 */
  tag?: string
  convId?: string
  /** 会话头像绝对地址（群→群头像 / 单聊→对方头像），拉取失败主进程兜底应用 logo */
  avatar?: string
  /** 无头像图时的字头像 PNG dataURL（页面 canvas 画出，与界面字头像同款；优先级低于 avatar） */
  avatarData?: string
}

interface DesktopHttpReq {
  url: string
  method?: string
  headers?: Record<string, string>
  body?: string
  bodyB64?: string
  timeout?: number
}

interface DesktopHttpResp {
  status: number
  statusText?: string
  headers?: Record<string, string>
  body?: string
  /** 网络层失败（无法连接/超时/DNS），有 HTTP 应答时为空 */
  error?: string
}

declare global {
  interface Window { imDesktop?: ImDesktopBridge }
}

const bridge = typeof window !== 'undefined' ? window.imDesktop : undefined

/** 桥是否存在（运行在桌面壳内）：桌面专属 UI 入口（登录页服务器设置/设置面板）以此为准 */
export const hasDesktop = !!bridge

/** 服务端根地址（浏览器/桌面开发模式为空 = 同源；桌面打包形态为用户配置的服务器地址） */
export const remoteBase = bridge && !bridge.dev ? (bridge.serverBase || '').replace(/\/+$/, '') : ''

/** 接口前缀：桌面按探测出的口径拼接——直连后端接口在根路径（''），反代口径带 /api；浏览器恒为 /api（同源） */
export const apiPrefix = remoteBase ? (bridge!.apiMode === 'direct' ? '' : '/api') : '/api'

/** axios 基址：桌面 = 服务器地址 + 口径前缀；浏览器 = 同源 /api */
export const apiBaseURL = remoteBase ? remoteBase + apiPrefix : '/api'

/** 登录 originDomain：桌面取服务器地址主机名（后端域名绑定校验按部署站点比对，不能传壳内回环地址） */
export function originHost(): string {
  if (remoteBase) {
    try { return new URL(remoteBase).hostname } catch { /* 非法地址退回壳内主机名 */ }
  }
  return location.hostname
}

/** 打开服务器设置窗口（桌面壳内有效；浏览器无桥静默忽略） */
export function openServerSetup() {
  try { bridge?.openServerSetup() } catch { /* 忽略 */ }
}

/** 显示并聚焦主窗口（通知点击回跳） */
export function showMainWindow() {
  try { bridge?.showWindow() } catch { /* 忽略 */ }
}

/** 上报未读数给桌面壳（托盘/任务栏角标；浏览器无桥忽略） */
export function reportBadge(count: number) {
  try { bridge?.badge(count) } catch { /* 忽略 */ }
}

/** 发新消息系统通知（桌面壳内有效；浏览器无桥忽略） */
export function notifyDesktop(payload: DesktopNotifyPayload) {
  try { void bridge?.notify(payload) } catch { /* 忽略 */ }
}

/** 注册通知点击回跳（页面加载时注册一次；浏览器无桥不注册） */
export function onNotifyClick(cb: (convId: string) => void) {
  try { bridge?.onNotifyClick(cb) } catch { /* 忽略 */ }
}

// -------------------- HTTP 转发适配器 --------------------

/**
 * 桌面壳内页面源是 http://127.0.0.1:*，跨源直连部署站点受浏览器 CORS 约束
 * （后端未配跨域时预检即失败），故 axios 请求经 IPC 交主进程 net 直连（无 CORS 概念）。
 * 仅桌面打包形态安装；浏览器与桌面开发模式仍走内置 XHR 适配器。
 */
export function installDesktopAdapter(instance: AxiosInstance) {
  if (!bridge || !remoteBase) return
  instance.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const rawHeaders: Record<string, any> = (config.headers as any)?.toJSON
      ? (config.headers as any).toJSON()
      : (config.headers || {})
    const headers: Record<string, string> = {}
    for (const [k, v] of Object.entries(rawHeaders)) {
      if (typeof v === 'string' && k.toLowerCase() !== 'content-length') headers[k] = v
    }
    let body: string | undefined
    let bodyB64: string | undefined
    const data = config.data as any
    if (data instanceof FormData) {
      const boundary = '----mseimform' + Date.now().toString(16) + Math.random().toString(16).slice(2)
      headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
      bodyB64 = await multipartB64(data, boundary)
    } else if (data != null) {
      body = typeof data === 'string' ? data : JSON.stringify(data)
      if (!Object.keys(headers).some(k => k.toLowerCase() === 'content-type')) headers['Content-Type'] = 'application/json'
    }
    const resp = await bridge!.httpFetch({
      url: buildRequestUrl(config),
      method: (config.method || 'get').toUpperCase(),
      headers, body, bodyB64,
      timeout: config.timeout || 15000,
    })
    if (resp.error) throw new AxiosError(resp.error, AxiosError.ERR_NETWORK, config)
    const response: AxiosResponse = {
      data: resp.body ?? '',
      status: resp.status,
      statusText: resp.statusText || '',
      headers: resp.headers || {},
      config,
      request: {},
    }
    // 与内置适配器同语义：validateStatus 不满足按 HTTP 错误抛出（携带 response 供上层区分 401/403 与网络故障）
    if (!config.validateStatus!(response.status)) {
      throw new AxiosError(
        'Request failed with status code ' + response.status,
        response.status >= 400 && response.status < 500 ? AxiosError.ERR_BAD_REQUEST : AxiosError.ERR_BAD_RESPONSE,
        config, undefined, response,
      )
    }
    return response
  }
}

/** FormData → multipart 报文（自设头像上传走此通道；文件字段读 arrayBuffer） */
async function multipartB64(form: FormData, boundary: string): Promise<string> {
  const enc = new TextEncoder()
  const chunks: Uint8Array[] = []
  for (const [name, value] of form.entries()) {
    if (typeof value === 'string') {
      chunks.push(enc.encode(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n`))
      chunks.push(enc.encode(value))
      chunks.push(enc.encode('\r\n'))
    } else {
      chunks.push(enc.encode(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"; filename="${value.name.replace(/"/g, '%22')}"\r\n`))
      chunks.push(enc.encode(`Content-Type: ${value.type || 'application/octet-stream'}\r\n\r\n`))
      chunks.push(new Uint8Array(await value.arrayBuffer()))
      chunks.push(enc.encode('\r\n'))
    }
  }
  chunks.push(enc.encode(`--${boundary}--\r\n`))
  let total = 0
  for (const c of chunks) total += c.length
  const all = new Uint8Array(total)
  let off = 0
  for (const c of chunks) { all.set(c, off); off += c.length }
  // 分段转二进制串再 btoa（避免超出 apply 参数上限）
  let bin = ''
  const step = 0x8000
  for (let i = 0; i < all.length; i += step) bin += String.fromCharCode(...all.subarray(i, i + step))
  return btoa(bin)
}

/** baseURL + url + params 拼最终请求地址（axios combineURL/buildURL 的轻量等价） */
function buildRequestUrl(config: InternalAxiosRequestConfig): string {
  let url = config.url || ''
  if (config.baseURL && !/^[a-z][a-z0-9+.-]*:\/\//i.test(url)) {
    url = config.baseURL.replace(/\/+$/, '') + '/' + url.replace(/^\/+/, '')
  }
  const params = config.params as Record<string, unknown> | undefined
  if (params) {
    const qs = Object.entries(params)
      .filter(([, v]) => v != null && v !== '')
      .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
      .join('&')
    if (qs) url += (url.includes('?') ? '&' : '?') + qs
  }
  return url
}
