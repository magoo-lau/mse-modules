/**
 * mse 服务探测：识别服务端地址的接口口径并验证确实是 mse 后端。
 *
 * 两种部署口径并存：
 * - direct：填后端根地址（单体 8080 / 网关 48080），接口在根路径（/auth/...）
 * - proxy：填反代/站点根地址，接口按 web 部署同款 /api 前缀转发（nginx 去前缀）
 *
 * 探测端点用 /auth/captcha/new（登录链路在用的验证码预检，免登录白名单，恒存在）：
 * 应答 {code:0,...} 才算 mse 服务在位——普通端口有应答但接口 404 会明确报「不是 mse 服务」。
 */
const { net } = require('electron')

const PROBE_PATH = '/auth/captcha/new'
const TIMEOUT_MS = 4000

/** 请求一个探测 URL：返回 { reached, mse, status }（mse = HTTP 200 且 JSON code===0） */
async function probeUrl(url) {
  try {
    const r = await net.fetch(url, { method: 'GET', signal: AbortSignal.timeout(TIMEOUT_MS) })
    let mse = false
    if (r.status === 200) {
      try { mse = JSON.parse(await r.text()).code === 0 } catch { /* 非 mse 应答格式 */ }
    }
    return { reached: true, mse, status: r.status }
  } catch (err) {
    return { reached: false, error: err }
  }
}

/**
 * 探测 base（已归一化的 http(s) 根地址）：先试直连口径，再试 /api 反代口径。
 * 返回 { ok, mode?, message?, error? }——两口径都未命中 mse 接口时 ok=false 并给出可读原因。
 */
async function detect(base) {
  let lastStatus = null
  for (const mode of ['direct', 'proxy']) {
    const url = base + (mode === 'proxy' ? '/api' : '') + PROBE_PATH
    const r = await probeUrl(url)
    if (r.reached && r.mse) {
      return {
        ok: true,
        mode,
        status: r.status,
        message: mode === 'direct'
          ? '连接成功：mse 服务直连可用（认证接口应答正常）'
          : '连接成功：mse 服务可达（/api 反代口径）',
      }
    }
    if (r.reached) lastStatus = r.status
  }
  if (lastStatus != null) {
    return {
      ok: false,
      error: `地址可达，但未找到 mse 认证接口（${PROBE_PATH} 直连与 /api 前缀均未命中，最后应答 HTTP ${lastStatus}），请确认填的是 mse 后端/部署站点地址`,
    }
  }
  return { ok: false, error: '无法连接服务器（网络不通或超时）' }
}

module.exports = { detect, PROBE_PATH }
