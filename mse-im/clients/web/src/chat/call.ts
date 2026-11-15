/**
 * 1v1 音视频通话（自研信令 + LiveKit）。
 *
 * 信令：createCustomMessage + sendMessage(isOnlineOnly)——在线消息不落库不进会话，
 * 双端通过 SDK 事件 OnRecvOnlineOnlyMessage 收到（App 负责转发给 handleSignal）。
 * 媒体：接通后各自向后端 POST /im/client/rtc/token 换 LiveKit 房间 token（room=callId），
 * 用 livekit-client 连 LiveKit Server 推拉流。
 *
 * 状态机：idle →(call)→ outgoing →(accept)→ connecting →(connected)→ active
 *         idle →(被叫)→ incoming →(accept)→ connecting → active
 * 任何一方 hangup/reject/cancel 或超时 → idle。
 */
import { reactive } from 'vue'
import { Room, RoomEvent, Track, type RemoteTrack, type RemoteTrackPublication } from 'livekit-client'
import type { WasmSdk } from '@openim/wasm-client-sdk'
import { http } from '../mse'
import type { CallSignal } from './custom'

export type CallPhase = 'idle' | 'outgoing' | 'incoming' | 'connecting' | 'active'

export interface CallPeer { userID: string; nickname: string; faceURL: string }

const CALL_TIMEOUT_MS = 30_000

export function createCallManager(opts: {
  getSdk: () => WasmSdk | null
  me: () => { userID: string; nickname: string; faceURL: string }
  /** 会话签发下发的 RTC 配置（mse.im.livekit.*）；enabled=false 时发起按钮置灰 */
  rtc: () => { enabled: boolean; url: string }
  /** 轻提示（复用 App 的 setStatus 通道） */
  toast: (msg: string) => void
}) {

  const state = reactive({
    phase: 'idle' as CallPhase,
    media: 'audio' as 'audio' | 'video',
    peer: null as CallPeer | null,
    callId: '',
    /** 本地/远端媒体流（CallOverlay 绑定到 video/audio 元素） */
    localStream: null as MediaStream | null,
    remoteStream: null as MediaStream | null,
    muted: false,
    camOff: false,
    startedAt: 0,
    error: '',
  })

  let room: Room | null = null
  let timer: number | null = null
  let remoteAudio: HTMLAudioElement | null = null

  function sendSignal(sig: CallSignal['sig'], to: { userID: string }, extra?: Partial<CallSignal>) {
    const sdk = opts.getSdk()
    if (!sdk) return
    const s: CallSignal = {
      customType: 'call',
      sig,
      callId: state.callId,
      media: state.media,
      room: state.callId,
      from: opts.me(),
      ts: Date.now(),
      ...extra,
    }
    sdk.createCustomMessage({ data: JSON.stringify(s), extension: '', description: '' })
      .then((r: any) => {
        const message = r?.data ?? r
        if (!message) return
        return sdk.sendMessage({ recvID: to.userID, groupID: '', message, isOnlineOnly: true })
      })
      .catch(() => { /* 信令尽力而为；超时/挂断兜底 */ })
  }

  function armTimer(fn: () => void, ms = CALL_TIMEOUT_MS) {
    clearTimer()
    timer = window.setTimeout(() => { timer = null; fn() }, ms)
  }
  function clearTimer() {
    if (timer) { clearTimeout(timer); timer = null }
  }

  async function fetchToken(): Promise<string> {
    const { data } = await http.post('/im/client/rtc/token', { room: state.callId })
    if (data.code !== 0) throw new Error(data.message || 'RTC token 签发失败')
    return data.data.token as string
  }

  async function connectRoom() {
    const rtcUrl = opts.rtc().url
    if (!rtcUrl) throw new Error('未配置 LiveKit 地址（mse.im.livekit.url）')
    const token = await fetchToken()
    room = new Room({ adaptiveStream: true, dynacast: true })
    room.on(RoomEvent.TrackSubscribed, (track: RemoteTrack, _pub: RemoteTrackPublication) => {
      if (track.kind === Track.Kind.Video) {
        state.remoteStream = track.mediaStream
      } else if (track.kind === Track.Kind.Audio) {
        // 远端音频不依赖 video 元素：独立 Audio 播放
        if (!remoteAudio) {
          remoteAudio = document.createElement('audio')
          remoteAudio.autoplay = true
        }
        remoteAudio.srcObject = track.mediaStream
        remoteAudio.play().catch(() => { /* 自动播放限制：用户手势链路内通常已解锁 */ })
      }
    })
    room.on(RoomEvent.TrackUnsubscribed, () => {
      if (!room?.remoteParticipants.some(p => p.videoTrackPublications.size > 0)) state.remoteStream = null
    })
    room.on(RoomEvent.Disconnected, () => { if (state.phase !== 'idle') end('对方已挂断') })
    await room.connect(rtcUrl, token)
    // 本地流：先开麦；视频通话再开摄像头
    await room.localParticipant.setMicrophoneEnabled(true)
    const local = new MediaStream()
    for (const pub of room.localParticipant.getTrackPublications().values()) {
      const t = pub.track?.mediaStreamTrack
      if (t) local.addTrack(t)
    }
    if (state.media === 'video') await room.localParticipant.setCameraEnabled(true)
    state.localStream = local
    state.phase = 'active'
    state.startedAt = Date.now()
    clearTimer()
  }

  function disconnectRoom() {
    try { room?.disconnect() } catch { /* 忽略 */ }
    room = null
    state.localStream = null
    state.remoteStream = null
    if (remoteAudio) { remoteAudio.srcObject = null }
  }

  function reset() {
    clearTimer()
    disconnectRoom()
    state.phase = 'idle'
    state.peer = null
    state.callId = ''
    state.muted = false
    state.camOff = false
    state.startedAt = 0
    state.error = ''
  }

  /** 主叫发起 */
  function startCall(peer: CallPeer, media: 'audio' | 'video') {
    if (state.phase !== 'idle') { opts.toast('当前正在通话中'); return }
    if (!opts.rtc().enabled) { opts.toast('通话能力未开通（mse.im.livekit.enabled）'); return }
    state.callId = crypto.randomUUID ? crypto.randomUUID() : `call-${Date.now()}`
    state.media = media
    state.peer = peer
    state.phase = 'outgoing'
    sendSignal('call', peer)
    armTimer(() => {
      if (state.phase !== 'outgoing') return
      sendSignal('cancel', peer)
      reset()
      opts.toast('无人接听')
    })
  }

  /** 被叫接听 */
  async function acceptCall() {
    if (state.phase !== 'incoming' || !state.peer) return
    const peer = state.peer
    sendSignal('accept', peer)
    state.phase = 'connecting'
    try {
      await connectRoom()
    } catch (e: any) {
      state.error = e?.message || String(e)
      sendSignal('hangup', peer)
      armTimer(reset, 1500)
    }
  }

  /** 被叫拒绝 */
  function rejectCall() {
    if (state.phase !== 'incoming' || !state.peer) return
    sendSignal('reject', state.peer)
    reset()
  }

  /** 任意一方挂断 */
  function hangUp() {
    const peer = state.peer
    if (peer && (state.phase === 'active' || state.phase === 'connecting')) sendSignal('hangup', peer)
    reset()
  }

  /** App 把收到的通话信令转发进来（按 isOnlineOnly 消息 content 解析结果） */
  function handleSignal(s: CallSignal) {
    if (!s || typeof s.callId !== 'string') return
    switch (s.sig) {
      case 'call': {
        if (state.phase !== 'idle') {
          sendSignal('busy', s.from)
          return
        }
        state.callId = s.callId
        state.media = s.media === 'video' ? 'video' : 'audio'
        state.peer = s.from
        state.phase = 'incoming'
        armTimer(() => {
          if (state.phase !== 'incoming') return
          reset()
          opts.toast('未接听')
        }, 45_000)
        break
      }
      case 'accept': {
        // 仅主叫关心；callId 校验防串线
        if (state.phase !== 'outgoing' || s.callId !== state.callId) return
        state.phase = 'connecting'
        armTimer(() => {
          if (state.phase !== 'connecting') return
          sendSignal('hangup', state.peer!)
          reset()
          opts.toast('连接超时')
        }, 20_000)
        connectRoom().catch((e: any) => {
          state.error = e?.message || String(e)
          sendSignal('hangup', state.peer!)
          armTimer(reset, 1500)
        })
        break
      }
      case 'reject': {
        if (s.callId !== state.callId) return
        if (state.phase === 'outgoing' || state.phase === 'connecting') {
          reset()
          opts.toast('对方已拒绝')
        }
        break
      }
      case 'cancel': {
        if (s.callId !== state.callId) return
        if (state.phase === 'incoming') {
          reset()
          opts.toast('对方已取消')
        }
        break
      }
      case 'busy': {
        if (s.callId !== state.callId) return
        if (state.phase === 'outgoing') {
          reset()
          opts.toast('对方正在通话中')
        }
        break
      }
      case 'hangup': {
        if (s.callId !== state.callId) return
        if (state.phase === 'active' || state.phase === 'connecting' || state.phase === 'outgoing') {
          reset()
          opts.toast('通话已结束')
        }
        break
      }
    }
  }

  /** 通话中媒体开关 */
  async function toggleMute() {
    state.muted = !state.muted
    await room?.localParticipant.setMicrophoneEnabled(!state.muted).catch(() => { /* 忽略 */ })
  }
  async function toggleCam() {
    state.camOff = !state.camOff
    await room?.localParticipant.setCameraEnabled(!state.camOff).catch(() => { /* 忽略 */ })
  }

  return { state, startCall, acceptCall, rejectCall, hangUp, handleSignal, toggleMute, toggleCam }
}

export type CallManager = ReturnType<typeof createCallManager>
