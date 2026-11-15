/**
 * 各类型消息的单行摘要（conversation 预览、引用条、合并转发 abstract 共用）。
 * 与后端 ImMsgAuditService.extractText 的口径保持一致（[图片]/[语音] xs/[文件] 名…）。
 */
import { isAppShare, isLinkCard, parseCustom } from './custom'
import { atTextDisplay, fmtSize } from './util'

export function summaryOf(m: any): string {
  if (!m) return ''
  switch (m.contentType) {
    case 101:
      return m.textElem?.content || ''
    case 102:
      return '[图片]'
    case 103:
      return `[语音] ${Math.max(1, Math.round(m.soundElem?.duration || 1))}"`
    case 104:
      return '[视频]'
    case 105:
      return `[文件] ${m.fileElem?.fileName || ''}${m.fileElem?.fileSize ? ' ' + fmtSize(m.fileElem.fileSize) : ''}`.trim()
    case 106:
      return atTextDisplay(m.atTextElem?.text)
    case 107:
      return `[聊天记录] ${m.mergeElem?.title || ''}`.trim()
    case 108:
      return `[名片] ${m.cardElem?.nickname || m.cardElem?.userID || ''}`.trim()
    case 109:
      return `[位置] ${m.locationElem?.description || ''}`.trim()
    case 114:
      return m.quoteElem?.text || ''
    case 110: {
      const v = parseCustom(m.customElem?.data)
      if (isAppShare(v)) return `[页面分享] ${v.title || v.url}`
      if (isLinkCard(v)) return `[链接] ${v.title || v.url}`
      if (v?.customType === 'call') return `[通话] ${v.media === 'video' ? '视频' : '语音'}通话`
      // 可解析成 JSON 的未知协议按卡片摘要展示（预览行不外泄原始 JSON 串）；纯文本 custom（机器人回复等）原样
      if (v && typeof v === 'object') return `[卡片] ${v.title || v.customType || ''}`.trim()
      return m.customElem?.description || m.customElem?.data || ''
    }
    case 113:
      return ''
    default:
      // 通知类（1xxx 撤回/群通知等）交给渲染层特殊处理，这里给通用文案
      if (m.contentType >= 1500 && m.contentType < 1600) return '[群通知]'
      if (m.contentType === 2101) return ''
      return '[新消息]'
  }
}
