/** 配置存取：userData/im-desktop-config.json（服务器地址 + 接口口径；随应用数据目录持久化） */
const fs = require('fs')
const path = require('path')

function configPath() {
  const { app } = require('electron')
  return path.join(app.getPath('userData'), 'im-desktop-config.json')
}

function loadConfig() {
  try {
    const cfg = JSON.parse(fs.readFileSync(configPath(), 'utf8'))
    return {
      serverBase: typeof cfg.serverBase === 'string' ? cfg.serverBase : '',
      // direct=后端根地址直连 / proxy=站点根 + /api 反代（缺省按反代口径，兼容旧版配置）
      apiMode: cfg.apiMode === 'direct' ? 'direct' : 'proxy',
    }
  } catch {
    return { serverBase: '', apiMode: 'proxy' }
  }
}

function saveConfig(cfg) {
  fs.mkdirSync(path.dirname(configPath()), { recursive: true })
  fs.writeFileSync(configPath(), JSON.stringify(cfg, null, 2), 'utf8')
}

/** 归一化：trim；缺协议补 http://；仅允许 http(s)；去末尾斜杠。非法返回空串 */
function normalizeServerBase(input) {
  let s = String(input || '').trim()
  if (!s) return ''
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(s)) s = 'http://' + s
  try {
    const u = new URL(s)
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return ''
    return s.replace(/\/+$/, '')
  } catch {
    return ''
  }
}

module.exports = { loadConfig, saveConfig, normalizeServerBase, configPath }
