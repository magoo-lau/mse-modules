/**
 * 应用图标生成（零依赖，纯 Node）：assets/icon.png(256) / tray.png(32) / badge.png(16)。
 * 512 画布逐像素 SDF 绘制（圆角方底 + 白色气泡尾巴 + 三粒绿点），再盒式降采样保证小图平滑。
 * 用法：node scripts/gen-icons.mjs（改配色/形状后重跑即可）
 */
import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ASSETS = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets')

// ---------- PNG 编码 ----------
const CRC_TABLE = (() => {
  const t = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c
  }
  return t
})()

function crc32(buf) {
  let c = -1
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}

function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}

function encodePng(size, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // RGBA
  const raw = Buffer.alloc((size * 4 + 1) * size)
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0 // filter: none
    rgba.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4)
  }
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))])
}

// ---------- SDF 绘制 ----------
/** 有符号距离 → 抗锯齿覆盖率 */
const cov = (d) => Math.max(0, Math.min(1, 0.5 - d))

function roundRectSDF(px, py, cx, cy, hw, hh, r) {
  const qx = Math.abs(px - cx) - hw + r
  const qy = Math.abs(py - cy) - hh + r
  return Math.min(Math.max(qx, qy), 0) + Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) - r
}

function circleSDF(px, py, cx, cy, r) {
  return Math.hypot(px - cx, py - cy) - r
}

/** src 叠加到 dst（alpha 合成），a ∈ [0,1] */
function blend(dst, src, a) {
  dst[0] = src[0] * a + dst[0] * (1 - a)
  dst[1] = src[1] * a + dst[1] * (1 - a)
  dst[2] = src[2] * a + dst[2] * (1 - a)
  dst[3] = Math.max(dst[3], a * 255)
}

const GREEN = [7, 193, 96] // #07C160 微信绿
const WHITE = [255, 255, 255]
const RED = [250, 81, 81] // #FA5151

/** 品牌图标：圆角方底（绿）+ 白色聊天气泡（带左下尾巴）+ 气泡内三粒绿点 */
function drawIcon(S) {
  const buf = Buffer.alloc(S * S * 4)
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const px = x + 0.5, py = y + 0.5
      const dst = [0, 0, 0, 0]
      // 底：整幅圆角方
      blend(dst, GREEN, cov(roundRectSDF(px, py, S / 2, S / 2, S * 0.48, S * 0.48, S * 0.21)))
      // 气泡主体 + 尾巴（两形状并集）
      const bubble = roundRectSDF(px, py, S / 2, S * 0.455, S * 0.30, S * 0.235, S * 0.14)
      const tail = roundRectSDF(px, py, S * 0.345, S * 0.645, S * 0.085, S * 0.075, S * 0.05)
      blend(dst, WHITE, cov(Math.min(bubble, tail)))
      // 三粒点
      for (const dx of [-0.105, 0, 0.105]) {
        blend(dst, GREEN, cov(circleSDF(px, py, S / 2 + dx * S, S * 0.455, S * 0.037)))
      }
      const o = (y * S + x) * 4
      buf[o] = Math.round(dst[0]); buf[o + 1] = Math.round(dst[1]); buf[o + 2] = Math.round(dst[2]); buf[o + 3] = Math.round(dst[3])
    }
  }
  return buf
}

/** 未读角标：红圆 + 白描边（任务栏叠标用） */
function drawBadge(S) {
  const buf = Buffer.alloc(S * S * 4)
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const px = x + 0.5, py = y + 0.5
      const dst = [0, 0, 0, 0]
      blend(dst, WHITE, cov(circleSDF(px, py, S / 2, S / 2, S * 0.47)))
      blend(dst, RED, cov(circleSDF(px, py, S / 2, S / 2, S * 0.36)))
      const o = (y * S + x) * 4
      buf[o] = Math.round(dst[0]); buf[o + 1] = Math.round(dst[1]); buf[o + 2] = Math.round(dst[2]); buf[o + 3] = Math.round(dst[3])
    }
  }
  return buf
}

/** 盒式降采样（factor 整数倍） */
function downsample(src, S, factor) {
  const T = S / factor
  const buf = Buffer.alloc(T * T * 4)
  for (let y = 0; y < T; y++) {
    for (let x = 0; x < T; x++) {
      let r = 0, g = 0, b = 0, a = 0
      for (let dy = 0; dy < factor; dy++) {
        for (let dx = 0; dx < factor; dx++) {
          const o = ((y * factor + dy) * S + (x * factor + dx)) * 4
          const al = src[o + 3] / 255
          r += src[o] * al; g += src[o + 1] * al; b += src[o + 2] * al; a += src[o + 3]
        }
      }
      const n = factor * factor
      const t = (y * T + x) * 4
      const avgA = a / n
      const covA = avgA / 255
      buf[t] = Math.round(r / n / (covA || 1))
      buf[t + 1] = Math.round(g / n / (covA || 1))
      buf[t + 2] = Math.round(b / n / (covA || 1))
      buf[t + 3] = Math.round(avgA)
    }
  }
  return { buf, size: T }
}

mkdirSync(ASSETS, { recursive: true })
const HI = 512
const hi = drawIcon(HI)

for (const [name, size] of [['icon.png', 256], ['tray.png', 32]]) {
  const { buf } = downsample(hi, HI, HI / size)
  writeFileSync(join(ASSETS, name), encodePng(size, buf))
  console.log(`assets/${name}  ${size}x${size}`)
}

writeFileSync(join(ASSETS, 'badge.png'), encodePng(16, drawBadge(16)))
console.log('assets/badge.png  16x16')
