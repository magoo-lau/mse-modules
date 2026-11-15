/**
 * customElem（contentType 110）的应用层协议约定。
 *
 * OpenIM 的 custom 消息是自由 JSON（data 字段），官方不内置业务语义；
 * 这里定义聊天客户端与机器人/工作台共用的三类扩展：
 *  - link-card 链接卡片：Composer 表单发送，机器人/外部系统也可按此协议推送；
 *  - app-share 页面分享卡片：工作台内嵌页「分享」发出（服务端 /im/client/share 代发），
 *    点击对同站点（webBase）地址签发一次性 code 免登直达基座页面；
 *  - call 通话信令：1v1 实时音视频的呼叫/接听/挂断走 isOnlineOnly 在线消息，
 *    不落库不进会话（开源 wasm SDK 无官方 signaling API，自研协议见 chat/call.ts）。
 */

export const CUSTOM_TYPE_LINK_CARD = 'link-card'
export const CUSTOM_TYPE_APP_SHARE = 'app-share'
export const CUSTOM_TYPE_CALL = 'call'

/** 链接卡片 payload（customElem.data 的 JSON） */
export interface LinkCardData {
  customType: typeof CUSTOM_TYPE_LINK_CARD
  title: string
  desc?: string
  url: string
  thumbUrl?: string
}

/** 页面分享卡片 payload（customElem.data 的 JSON，服务端 ImShareService.AppShareCard 同构）；
 *  icon 可空（旧消息无此字段）：图片地址/Element Plus 图标名/emoji，空时回退 🧩 */
export interface AppShareData {
  customType: typeof CUSTOM_TYPE_APP_SHARE
  title: string
  desc?: string
  url: string
  icon?: string
}

/** 通话信令 payload（customElem.data 的 JSON，仅在线消息） */
export interface CallSignal {
  customType: typeof CUSTOM_TYPE_CALL
  /** call=呼叫 accept=接通 reject=拒绝 cancel=主叫取消（超时/撤回） hangup=挂断 busy=占线 */
  sig: 'call' | 'accept' | 'reject' | 'cancel' | 'hangup' | 'busy'
  /** 全程唯一通话 ID，同时作为 LiveKit 房间名 */
  callId: string
  media: 'audio' | 'video'
  room: string
  from: { userID: string; nickname: string; faceURL: string }
  ts: number
}

/** 解析 customElem.data：业务 JSON 优先，失败回落 null（渲染端按纯文本兜底） */
export function parseCustom(data: string | undefined): any | null {
  if (!data) return null
  try {
    const v = JSON.parse(data)
    return v && typeof v === 'object' ? v : null
  } catch {
    return null
  }
}

export function isLinkCard(v: any): v is LinkCardData {
  return !!v && v.customType === CUSTOM_TYPE_LINK_CARD && typeof v.url === 'string' && !!v.url
}

export function isAppShare(v: any): v is AppShareData {
  return !!v && v.customType === CUSTOM_TYPE_APP_SHARE && typeof v.url === 'string' && !!v.url
}

export function isCallSignal(v: any): v is CallSignal {
  return !!v && v.customType === CUSTOM_TYPE_CALL && typeof v.sig === 'string' && typeof v.callId === 'string'
}
