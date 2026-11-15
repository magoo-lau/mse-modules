/** 展示层小工具：时间 / 文件大小 / 剪贴板 / 站内素材地址 / 机器人判定 / 菜单图标 */
import type { Component } from 'vue'
import * as EpIcons from '@element-plus/icons-vue'
import { remoteBase, apiPrefix } from '../desktop'

/** bot_ 前缀账号（机器人）：未配置头像时各渲染点统一兜底绿底 🤖（与通讯录/资料卡一致） */
export function isBotUserId(id: string | null | undefined): boolean {
  return !!id && id.startsWith('bot_')
}

/** Element Plus 图标名 → 图标组件（与基座 sys_menu.icon 同一体系：'Box'/'Setting' 等，按名动态渲染）；
 *  非图标名（emoji/图片地址/任意文本）返回 undefined 交由调用方走各自分支 */
const EP_ICONS = EpIcons as Record<string, Component>
export function epIconComp(name: string | null | undefined): Component | undefined {
  const n = (name || '').trim()
  return n && EP_ICONS[n] ? EP_ICONS[n] : undefined
}

/** AtText 的 @全员标记还原：标记串大小写随服务端版本有差（atAllTag/AtAllTag），统一大小写不敏感替换回「@所有人」 */
export function atTextDisplay(text: string | null | undefined): string {
  return (text || '').replace(/atalltag/gi, '@所有人')
}

/**
 * 站内素材地址解析：绝对地址（http(s)/data:/blob:）原样返回；站内相对路径
 * （/fs/、/im/、/system/ 开头，mse-fs 公开素材、本地兜底素材、基座头像等）加接口前缀
 * 走后端（浏览器：dev 由 vite 代理、生产由 nginx 同约定转发，与基座 resolveAssetSrc 同款；
 * 桌面端：加服务器根地址 + 口径前缀成绝对地址——壳内页面源是回环地址，相对路径会打到本地静态服务器）。
 */
export function assetUrl(url: string | null | undefined): string {
  const u = (url || '').trim()
  if (!u) return ''
  if (/^(https?:|data:|blob:)/i.test(u)) return u
  if (/^\/(fs|im|system)\//.test(u)) return remoteBase + apiPrefix + u
  return u
}

/** 消息时间：今天 HH:mm、昨天「昨天 HH:mm」、更早 M/D HH:mm；列表场景 short=true 只到天 */
export function fmtTime(ts: number, short = false): string {
  if (!ts) return ''
  const d = new Date(ts), now = new Date()
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (d.toDateString() === now.toDateString()) return short ? hm : hm
  if (d.toDateString() === new Date(now.getTime() - 86400000).toDateString()) return short ? '昨天' : `昨天 ${hm}`
  return `${d.getMonth() + 1}/${d.getDate()}${short ? '' : ' ' + hm}`
}

/** 日期分隔符文案：今天/昨天/M月D日（跨年带年份） */
export function fmtDay(ts: number): string {
  const d = new Date(ts), now = new Date()
  if (d.toDateString() === now.toDateString()) return '今天'
  if (d.toDateString() === new Date(now.getTime() - 86400000).toDateString()) return '昨天'
  const y = d.getFullYear() !== now.getFullYear() ? `${d.getFullYear()}年` : ''
  return `${y}${d.getMonth() + 1}月${d.getDate()}日`
}

/** 文件大小：B/KB/MB/GB */
export function fmtSize(n: number | undefined): string {
  if (!n || n < 0) return ''
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** 语音/视频时长：秒 → m"ss' 样式（1'05"） */
export function fmtDuration(sec: number | undefined): string {
  const s = Math.max(0, Math.round(sec || 0))
  if (s < 60) return `${s}"` 
  return `${Math.floor(s / 60)}'${String(s % 60).padStart(2, '0')}"`
}

/** 剪贴板复制：clipboard API 优先，execCommand 隐藏 textarea 兜底（http 非 localhost 环境） */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch { /* 走兜底 */ }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

/** 文件扩展名（小写、不含点；无扩展名返回空串） */
export function fileExt(name: string | undefined): string {
  const i = (name || '').lastIndexOf('.')
  return i > 0 ? name.slice(i + 1).toLowerCase() : ''
}

/** 按扩展名取文件类型示意字符（用于文件气泡图标位） */
export function fileIconChar(name: string | undefined): string {
  switch (fileExt(name)) {
    case 'doc': case 'docx': return 'W'
    case 'xls': case 'xlsx': case 'csv': return 'X'
    case 'ppt': case 'pptx': return 'P'
    case 'pdf': return 'P'
    case 'zip': case 'rar': case '7z': case 'gz': case 'tar': return 'Z'
    case 'mp3': case 'wav': case 'm4a': case 'ogg': return '♪'
    case 'mp4': case 'mov': case 'avi': case 'mkv': case 'webm': return '▶'
    case 'png': case 'jpg': case 'jpeg': case 'gif': case 'webp': case 'bmp': return '🖼'
    case 'txt': case 'md': case 'log': return 'T'
    default: return '≡'
  }
}
