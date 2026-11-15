/**
 * 媒体素材准备：选图/选文件/选视频/录音，并产出 OpenIM create*MessageByFile 所需元数据。
 *
 * 上传链路：sendMessage 对 ByFile 消息自动经 apiAddr /object/* 预签名直传 MinIO
 * （进度走 SdkEvent.OnProgress），前端只负责 File 与尺寸/封面等元数据。
 */
import type { WasmSdk } from '@openim/wasm-client-sdk'
import type { Picture } from '@openim/wasm-client-sdk'

const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`)

function pick(accept: string): Promise<File | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = accept
    input.style.display = 'none'
    document.body.appendChild(input)
    const done = (f: File | null) => { input.remove(); resolve(f) }
    input.onchange = () => done(input.files?.[0] ?? null)
    // 取消（焦点回来且无变更）兜底关闭
    window.addEventListener('focus', () => setTimeout(() => { if (!input.files?.length) done(null) }, 300), { once: true })
    input.click()
  })
}

/** 读取图片宽高 */
function imageMeta(file: File): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => { URL.revokeObjectURL(url); resolve({ width: img.naturalWidth, height: img.naturalHeight }) }
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片解析失败')) }
    img.src = url
  })
}

/** 压缩缩略图（最长边 max）→ File，用作 snapshotPicture */
function shrinkImage(file: File, max = 256): Promise<File> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      const scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight))
      const w = Math.max(1, Math.round(img.naturalWidth * scale))
      const h = Math.max(1, Math.round(img.naturalHeight * scale))
      const canvas = document.createElement('canvas')
      canvas.width = w; canvas.height = h
      canvas.getContext('2d')!.drawImage(img, 0, 0, w, h)
      canvas.toBlob(b => resolve(b ? new File([b], `snap_${file.name.replace(/\.[^.]+$/, '')}.jpg`, { type: 'image/jpeg' }) : file), 'image/jpeg', 0.8)
    }
    img.onerror = () => { URL.revokeObjectURL(url); resolve(file) }
    img.src = url
  })
}

function picture(file: File, width: number, height: number): Picture {
  return { uuid: uuid(), type: file.type || 'image/jpeg', size: file.size, width, height, url: '' }
}

export interface ImagePick { file: File; width: number; height: number; snapshot: File; snapshotWidth: number; snapshotHeight: number }

/** 选择图片并产出源图/缩略图元数据（用户取消返回 null） */
export async function pickImage(): Promise<ImagePick | null> {
  const file = await pick('image/*')
  if (!file) return null
  try {
    const { width, height } = await imageMeta(file)
    const snap = await shrinkImage(file)
    const sm = await imageMeta(snap)
    return { file, width, height, snapshot: snap, snapshotWidth: sm.width, snapshotHeight: sm.height }
  } catch {
    // 解析失败仍按原文件发送（宽高 0，SDK/服务端容忍）
    return { file, width: 0, height: 0, snapshot: file, snapshotWidth: 0, snapshotHeight: 0 }
  }
}

export async function pickAnyFile(): Promise<File | null> {
  return pick('')
}

export interface VideoPick {
  file: File; duration: number; width: number; height: number
  snapshot: File; snapshotWidth: number; snapshotHeight: number
}

/** 视频元数据 + 指定时刻截帧 */
function videoMeta(file: File, seek: number): Promise<{ duration: number; width: number; height: number; snapshot: File | null }> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file)
    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    const finish = (snapshot: File | null) => {
      URL.revokeObjectURL(url)
      resolve({ duration: isFinite(video.duration) ? video.duration : 0, width: video.videoWidth, height: video.videoHeight, snapshot })
    }
    video.onloadedmetadata = () => {
      video.currentTime = isFinite(video.duration) ? Math.min(seek, Math.max(0, video.duration - 0.1)) : 0
    }
    video.onseeked = () => {
      try {
        const w = video.videoWidth, h = video.videoHeight
        if (!w || !h) return finish(null)
        const canvas = document.createElement('canvas')
        canvas.width = w; canvas.height = h
        canvas.getContext('2d')!.drawImage(video, 0, 0, w, h)
        canvas.toBlob(b => finish(b ? new File([b], 'video_snap.jpg', { type: 'image/jpeg' }) : null), 'image/jpeg', 0.8)
      } catch { finish(null) }
    }
    video.onerror = () => finish(null)
    // 解析兜底：6s 拿不到元数据就放弃封面
    setTimeout(() => finish(null), 6000)
    video.src = url
  })
}

/** 选择视频并产出时长/宽高/封面帧（用户取消返回 null） */
export async function pickVideo(): Promise<VideoPick | null> {
  const file = await pick('video/*')
  if (!file) return null
  const meta = await videoMeta(file, 1)
  const snap = meta.snapshot ?? new File([new Blob([''])], 'video_snap.jpg', { type: 'image/jpeg' })
  return {
    file,
    duration: Math.round(meta.duration),
    width: meta.width,
    height: meta.height,
    snapshot: snap,
    snapshotWidth: meta.width || 0,
    snapshotHeight: meta.height || 0,
  }
}

export interface SoundPick { file: File; duration: number }

/** 录音控制（MediaRecorder webm/opus；不支持时抛错由 UI 提示） */
export class VoiceRecorder {
  private recorder: MediaRecorder | null = null
  private chunks: BlobPart[] = []
  private startedAt = 0
  private timer: number | null = null

  get supported() { return !!(navigator.mediaDevices?.getUserMedia && window.MediaRecorder) }

  /** 开始录音；权限失败抛错由调用方提示。到达 maxSeconds 自动停止并回调 onAutoStop（否则结果无人接收会丢录音） */
  async start(onTick: (sec: number) => void, maxSeconds = 60, onAutoStop?: (r: SoundPick | null) => void) {
    if (this.recorder) return
    this.chunks = []
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus'
      : MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : ''
    this.recorder = mime ? new MediaRecorder(stream, { mimeType: mime }) : new MediaRecorder(stream)
    this.recorder.ondataavailable = e => { if (e.data.size) this.chunks.push(e.data) }
    this.recorder.start()
    this.startedAt = Date.now()
    this.timer = window.setInterval(() => {
      const sec = Math.floor((Date.now() - this.startedAt) / 1000)
      onTick(sec)
      if (sec >= maxSeconds) this.stop().then(r => onAutoStop?.(r))
    }, 250)
  }

  /** 停止并返回录音文件（duration 为秒；取消时 discard=true 仅释放资源） */
  stop(discard = false): Promise<SoundPick | null> {
    return new Promise((resolve) => {
      if (this.timer) { clearInterval(this.timer); this.timer = null }
      const rec = this.recorder
      if (!rec) return resolve(null)
      const duration = Math.max(1, Math.round((Date.now() - this.startedAt) / 1000))
      this.recorder = null
      rec.onstop = () => {
        rec.stream.getTracks().forEach(t => t.stop())
        if (discard || !this.chunks.length) return resolve(null)
        const blob = new Blob(this.chunks, { type: rec.mimeType || 'audio/webm' })
        resolve({ file: new File([blob], `voice_${Date.now()}.webm`, { type: blob.type }), duration })
      }
      rec.stop()
    })
  }
}

/* ---------- create*MessageByFile 参数组装（字段名以 wasm-client-sdk params.d.ts 为准） ---------- */

export function buildImageMessage(sdk: WasmSdk, p: ImagePick) {
  return sdk.createImageMessageByFile({
    file: p.file,
    sourcePath: p.file.name,
    sourcePicture: picture(p.file, p.width, p.height),
    bigPicture: picture(p.file, p.width, p.height),
    snapshotPicture: picture(p.snapshot, p.snapshotWidth, p.snapshotHeight),
  })
}

export function buildFileMessage(sdk: WasmSdk, file: File) {
  return sdk.createFileMessageByFile({
    file,
    filePath: file.name,
    fileName: file.name,
    uuid: uuid(),
    sourceUrl: '',
    fileSize: file.size,
    fileType: file.type || undefined,
  })
}

export function buildSoundMessage(sdk: WasmSdk, p: SoundPick) {
  return sdk.createSoundMessageByFile({
    file: p.file,
    uuid: uuid(),
    soundPath: p.file.name,
    sourceUrl: '',
    dataSize: p.file.size,
    duration: p.duration,
    soundType: p.file.type || 'audio/webm',
  })
}

export function buildVideoMessage(sdk: WasmSdk, p: VideoPick) {
  return sdk.createVideoMessageByFile({
    videoFile: p.file,
    snapshotFile: p.snapshot,
    videoPath: p.file.name,
    videoUUID: uuid(),
    videoUrl: '',
    videoType: p.file.type || 'video/mp4',
    videoSize: p.file.size,
    duration: p.duration,
    snapshotPath: 'video_snap.jpg',
    snapshotUUID: uuid(),
    snapshotSize: p.snapshot.size,
    snapshotUrl: '',
    snapshotWidth: p.snapshotWidth,
    snapshotHeight: p.snapshotHeight,
  })
}
