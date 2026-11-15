import axios from 'axios'
import { apiBaseURL, installDesktopAdapter, originHost, remoteBase } from './desktop'

/** mse 侧接口：浏览器 = 同源 /api（vite dev 代理或反代转发）；桌面 = 服务器地址 + /api（IPC 转发主进程直连） */
export const http = axios.create({ baseURL: apiBaseURL, timeout: 15000 })
installDesktopAdapter(http)

/**
 * HTTP 层错误转中文友好提示：axios 对非 2xx 直接抛 "Request failed with status code 500"
 * 这类英文原句（后端 Result 里的中文 message 根本没机会被读到），统一在拦截器里按状态码
 * 翻译成用户能懂的话。只改写 error.message，保留 AxiosError 本体（.response / .code），
 * 供调用方既有判定继续工作（如 401/403 清档回登录页）。
 */
http.interceptors.response.use(
  (resp) => resp,
  (error: any) => {
    if (axios.isAxiosError(error)) {
      const status = error.response?.status
      if (!error.response) {
        error.message = error.code === 'ECONNABORTED' ? '请求超时，请稍后重试' : '网络连接失败，请检查网络或服务器地址'
      } else if (status === 401) {
        error.message = '登录已过期，请重新登录'
      } else if (status === 403) {
        error.message = '没有操作权限'
      } else if (status === 404) {
        error.message = '服务暂不可用（接口不存在或版本不匹配）'
      } else if (status === 413) {
        error.message = '内容过大，超出服务端允许的大小'
      } else if (status != null && status >= 500) {
        error.message = '服务器开小差了，请稍后重试'
      } else {
        // 其余 4xx：优先透出后端 Result 里的中文 message
        const serverMsg = (error.response?.data as any)?.message
        error.message = (typeof serverMsg === 'string' && serverMsg) || `请求失败（${status ?? '未知错误'}）`
      }
      // axios 对 HTTP 错误的 .code 是字符串（ERR_BAD_REQUEST 等），覆盖为数字状态码，
      // 调用方的 401/403 凭证失效判定对 HTTP 层错误才能生效
      if (status != null) error.code = status
    }
    return Promise.reject(error)
  },
)

export const setToken = (t: string) => http.defaults.headers.common['Authorization'] = t
export const clearToken = () => delete http.defaults.headers.common['Authorization']

// -------------------- 认证存档（刷新/重开浏览器静默恢复登录） --------------------

/** 存档键：仅存 token + 登录名（token 服务端 30 天有效；IM token 每次恢复时重签不落盘）。
 *  serverBase 为桌面壳留存档来源站点：与当前配置不一致时视为失效（换服务器后不拿 A 站 token 打 B 站） */
const AUTH_KEY = 'im-web-auth'

export interface SavedAuth { token: string; tenantCode: string; username: string; serverBase?: string }

export function saveAuth(auth: SavedAuth) {
  try { localStorage.setItem(AUTH_KEY, JSON.stringify({ ...auth, serverBase: remoteBase })) } catch { /* 忽略 */ }
}

export function loadAuth(): SavedAuth | null {
  try {
    const raw = localStorage.getItem(AUTH_KEY)
    if (!raw) return null
    const auth = JSON.parse(raw) as SavedAuth
    if (!auth?.token) return null
    // 仅对带 serverBase 标记的存档做站点比对（旧版浏览器存档无此字段，升级后不强制登出）
    if (auth.serverBase != null && auth.serverBase !== remoteBase) { clearAuth(); return null }
    return auth
  } catch { return null }
}

export function clearAuth() {
  try { localStorage.removeItem(AUTH_KEY) } catch { /* 忽略 */ }
}

// -------------------- 登录链路（复用基座 /auth：验证码/双因子/域名绑定校验全部继承） --------------------

/** 图形验证码挑战（required=false 时登录页隐藏验证码行；risk 模式失败计数达标自动浮出） */
export interface CaptchaChallenge { required: boolean; captchaId: string | null; image: string | null }

/** TOTP 绑定挑战（二维码由后端渲染 base64 data URL，前端零依赖） */
export interface TotpEnrollment { secret: string; otpauthUri: string; qrImage: string }

/** 双因子阶段挑战：enroll=true 附绑定二维码/密钥；否则附邮箱通道可用性与脱敏邮箱 */
export interface TwoFactorChallenge {
  enroll: boolean
  enrollment: TotpEnrollment | null
  emailAvailable: boolean
  maskedEmail: string | null
}

/** 登录态（/auth/login 两段式的终态产物） */
export interface LoginResult { tokenValue: string; userId: string; nickname: string }

/** 登录响应（两段式）：twoFactorRequired=true 时持 tempToken 走 twoFactorLogin */
export interface LoginResponse {
  twoFactorRequired: boolean
  totpEnroll: boolean
  tempToken: string | null
  result: LoginResult | null
}

/** 登录页验证码预检（不需要时 required=false；预检失败不阻断登录——主链路还会强制复核） */
export async function captchaNew(tenantCode: string, username: string): Promise<CaptchaChallenge> {
  try {
    const { data } = await http.get('/auth/captcha/new', {
      params: { tenantCode: tenantCode || undefined, username: username || undefined }
    })
    if (data.code !== 0) return { required: false, captchaId: null, image: null }
    return { required: !!data.data?.required, captchaId: data.data?.captchaId || null, image: data.data?.image || null }
  } catch {
    return { required: false, captchaId: null, image: null }
  }
}

/** 租户登录第一段：验证码/域名绑定校验继承基座；双因子账号返回 tempToken 走 secondFactor */
export async function login(tenantCode: string, username: string, password: string,
                            captcha?: { captchaId?: string; captchaCode?: string }): Promise<LoginResponse> {
  const { data } = await http.post('/auth/login', {
    tenantCode, username, password, originDomain: originHost(), device: 'pc',
    captchaId: captcha?.captchaId || undefined,
    captchaCode: captcha?.captchaCode || undefined
  })
  if (data.code !== 0) throw new Error(data.message || '登录失败')
  return data.data as LoginResponse
}

/** 双因子挑战信息（凭临时凭证）：enroll=true 返回绑定二维码/密钥；否则返回邮箱通道可用性 */
export async function twoFactorChallenge(tempToken: string): Promise<TwoFactorChallenge> {
  const { data } = await http.get('/auth/2fa/challenge', { params: { tempToken } })
  if (data.code !== 0) throw new Error(data.message || '获取双因子信息失败')
  const d = data.data || {}
  return { enroll: !!d.enroll, enrollment: d.enrollment || null, emailAvailable: !!d.emailAvailable, maskedEmail: d.maskedEmail || null }
}

/** 发送邮箱动态码（凭临时凭证；返回脱敏邮箱展示目标） */
export async function sendTwoFactorEmailCode(tempToken: string): Promise<string> {
  const { data } = await http.post('/auth/2fa/email-code', { tempToken })
  if (data.code !== 0) throw new Error(data.message || '发送邮箱验证码失败')
  return data.data || ''
}

/** 双因子验证换正式登录态（channel：totp 缺省 / email；临时凭证 5 分钟有效、限 5 次尝试） */
export async function twoFactorLogin(tempToken: string, code: string, channel?: 'totp' | 'email'): Promise<LoginResult> {
  const { data } = await http.post('/auth/login/2fa', { tempToken, code, channel })
  if (data.code !== 0) throw new Error(data.message || '双因子验证失败')
  return data.data.result as LoginResult
}

/** IM 会话签发（失败时错误对象携带 .code：业务码或 HTTP 状态码，供调用方区分凭证失效与网络故障） */
export async function imSession(platformID = 5) {
  try {
    const { data } = await http.post('/im/client/session', { platformID })
    if (data.code !== 0) {
      const err = new Error(data.message || 'IM 会话签发失败') as Error & { code?: number }
      err.code = data.code
      throw err
    }
    return data.data as {
      userID: string; imToken: string; apiAddr: string; wsAddr: string
      expireAt: number; nickname: string; faceURL: string | null
      /** 音视频通话能力（mse.im.livekit.*；enabled=false 客户端隐藏发起入口） */
      rtc?: { enabled: boolean; url: string }
    }
  } catch (e: any) {
    if (e?.code == null && e?.response?.status != null) e.code = e.response.status
    throw e
  }
}

/** 工作台数据源（与 mse-ui 同款）：菜单树 + 权限 + 子应用 */
export async function profile() {
  const { data } = await http.get('/system/profile/me')
  if (data.code !== 0) throw new Error(data.message || '拉取工作台失败')
  return data.data as {
    menus: any[]; permissions: string[]
    microApps: { name: string; title?: string; prodEntry?: string }[]
  }
}

/** 工作台跳转：一次性 code → 带 ?ssoCode= 的完整 URL（浏览器打开即免登） */
export async function ssoJump(targetUrl: string) {
  const { data } = await http.post('/im/sso/code', { targetUrl })
  if (data.code !== 0) throw new Error(data.message || '签发跳转链接失败')
  return data.data.url as string
}

/** 工作台配置项（entryType 0=mse 页面菜单 1=外部应用；openMode 0=内嵌 1=弹窗；authMode 0=直连 1=免登授权；status 0=启用 1=停用；groupName 空=按菜单树目录推导） */
export interface WbCfgItem {
  id?: string | number
  entryType?: number
  path: string
  title?: string
  icon?: string
  groupName?: string
  openMode?: number
  authMode?: number
  status?: number
}

/** 工作台视图：跳转基址（租户配置 > 全局配置 > 缺省）+ 入口项（仅启用行；空数组 → 客户端回退全量默认展示） */
export async function workbench() {
  const { data } = await http.get('/im/workbench')
  if (data.code !== 0) return { webBaseUrl: 'http://localhost:3000', items: [] as WbCfgItem[] }
  const d = data.data || {}
  return { webBaseUrl: d.webBaseUrl || 'http://localhost:3000', items: (d.items || []) as WbCfgItem[] }
}

/** 外部应用入口跳转：直连返回原 URL；免登授权返回带一次性授权码 code 的回调地址 */
export async function wbJump(id: string | number) {
  const { data } = await http.post('/im/workbench/jump', { id })
  if (data.code !== 0) throw new Error(data.message || '签发跳转链接失败')
  return data.data.url as string
}

// -------------------- 通讯录 / 群组 --------------------

/** 通讯录部门（leaderUserId=部门负责人） */
export interface ContactDept { id: number; parentId: number; name: string; leaderUserId: number | null }

/** 通讯录用户（启用中；avatar 仅 http(s) 直链） */
export interface ContactUser { userId: number; nickname: string; avatar: string | null; deptId: number | null }

/** 通讯录机器人（bot_ 前缀，公众号式分组展示） */
export interface ContactBot { botUserId: string; nickname: string; faceUrl: string | null; agentCode: string }

/** 通讯录全量：部门 + 启用用户 + 启用机器人 + 我所在的部门群 id 集 */
export async function getContacts() {
  const { data } = await http.get('/im/client/contacts')
  if (data.code !== 0) throw new Error(data.message || '拉取通讯录失败')
  const d = data.data || {}
  return {
    depts: (d.depts || []) as ContactDept[],
    users: (d.users || []) as ContactUser[],
    bots: (d.bots || []) as ContactBot[],
    /** 部门群 groupId 集（对账自动维护；UI 据此隐藏「退群」——退了会被对账拉回） */
    deptGroupIds: (d.deptGroupIds || []) as string[],
  }
}

/** 按需注册：把用户注册进 IM 实例（对未登录过 IM 的用户发起单聊/建群前兜底）。
 *  ID 用后端下发的字符串原样回传——19 位雪花 ID 强转 Number 会丢末几位 */
export async function ensureUsers(userIds: Array<number | string>) {
  if (!userIds.length) return
  const { data } = await http.post('/im/client/contacts/ensure', { userIds })
  if (data.code !== 0) throw new Error(data.message || '用户注册失败')
}

/** 页面分享：把基座页面卡片（app-share custom 消息）以当前用户身份发给联系人/群聊，返回送达目标数；
 *  icon 为卡片图标（入口有效图标：emoji/Element Plus 图标名/图片地址，可空） */
export async function sharePage(payload: { userIds: string[]; groupIds: string[]; title: string; desc?: string; url: string; icon?: string }) {
  const { data } = await http.post('/im/client/share', payload)
  if (data.code !== 0) throw new Error(data.message || '分享失败')
  return data.data as number
}

/** 建群：群主=当前用户；name 缺省按成员昵称生成；机器人可混选 */
export async function createGroup(payload: { name?: string; memberUserIds: number[]; botUserIds?: string[] }) {
  const { data } = await http.post('/im/client/group', payload)
  if (data.code !== 0) throw new Error(data.message || '建群失败')
  return data.data as { groupId: string; groupName: string }
}

/** 邀请进群（仅群主/群管理员，服务端强制校验） */
export async function inviteMembers(groupId: string, payload: { userIds: number[]; botUserIds?: string[] }) {
  const { data } = await http.post('/im/client/group/invite', { groupId, ...payload })
  if (data.code !== 0) throw new Error(data.message || '邀请失败')
}

// -------------------- 用户资料 / 自设头像 --------------------

/** 聊天对象资料卡（用户；avatar 为生效头像地址，站内相对路径经 assetUrl 解析） */
export interface UserProfile {
  userId: number; username: string; nickname: string; avatar: string | null
  mobile: string | null; email: string | null; deptPath: string | null
}

export async function getUserProfile(userId: number | string) {
  const { data } = await http.get(`/im/client/user/${userId}/profile`)
  if (data.code !== 0) throw new Error(data.message || '拉取资料失败')
  return data.data as UserProfile
}

/** 上传自设头像（≤2MB png/jpg/jpeg/gif/webp），返回生效头像地址 */
export async function uploadAvatar(file: File) {
  const body = new FormData()
  body.append('file', file)
  const { data } = await http.post('/im/client/profile/avatar', body)
  if (data.code !== 0) throw new Error(data.message || '头像上传失败')
  return data.data as string | null
}

/** 重置自设头像（回落基座 sys_user.avatar），返回生效头像地址（null=首字母兜底） */
export async function resetAvatar() {
  const { data } = await http.post('/im/client/profile/avatar/reset')
  if (data.code !== 0) throw new Error(data.message || '头像重置失败')
  return data.data as string | null
}
