/**
 * 本地静态服务器：桌面内置 web 产物经 127.0.0.1 回环加载。
 * 页面/资产全部本地化（不从服务器加载）；走 http 回环而非 file:// / 自定义协议，
 * 是为了让页面保持「可信源」：getUserMedia（录音/通话）、剪贴板、通知权限全可用，
 * 且允许连 OpenIM 的 ws:// 明文网关（https 源会被 Mixed Content 拦截）。
 */
const http = require('http')
const fs = require('fs')
const path = require('path')

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.wasm': 'application/wasm',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json',
  '.txt': 'text/plain; charset=utf-8',
}

/** 依次尝试首选端口（本机其他应用占用时顺延），返回 { server, port }。
 *  roots：按序查找的目录集（第一个为主根，后续作补充别名，如设置页复用应用图标） */
function start(roots, preferredPorts) {
  const fallbackPorts = [4180, 4181, 4182, 4183, 4184, 4185, 4186, 4187, 4188, 4189, 4190, 4191]
  return new Promise((resolve, reject) => {
    const tryListen = (ports) => {
      const port = ports[0]
      if (port == null) return reject(new Error('本地回环端口均已占用（' + fallbackPorts[0] + '-' + fallbackPorts[fallbackPorts.length - 1] + '）'))
      const server = http.createServer(handler)
      server.on('error', () => tryListen(ports.slice(1)))
      server.listen(port, '127.0.0.1', () => resolve({ server, port }))
    }
    const handler = (req, res) => {
      try {
        let urlPath = decodeURIComponent((req.url || '/').split('?')[0])
        if (urlPath.endsWith('/')) urlPath += 'index.html'
        const rel = path.normalize(urlPath).replace(/^([\\/])/, '')
        for (const root of roots) {
          const file = path.normalize(path.join(root, rel))
          if (file !== path.normalize(root) && !file.startsWith(path.normalize(root) + path.sep)) continue
          try {
            const body = fs.readFileSync(file)
            res.writeHead(200, {
              'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
              'Cache-Control': 'no-cache',
            })
            return res.end(body)
          } catch { /* 本根未命中，试下一根 */ }
        }
        throw new Error('not found')
      } catch {
        // SPA 兜底：未命中文件一律回主根 index.html（单页应用，缺产物时给出可读提示）
        try {
          const body = fs.readFileSync(path.join(roots[0], 'index.html'))
          res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' })
          res.end(body)
        } catch {
          res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
          res.end('桌面 web 产物缺失：请先在 clients/web 执行 npm run build:desktop，再运行 npm run sync-web')
        }
      }
    }
    tryListen((preferredPorts && preferredPorts.length ? preferredPorts : fallbackPorts).slice())
  })
}

module.exports = { start }
