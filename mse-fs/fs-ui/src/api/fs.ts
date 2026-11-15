import request from './request'

// ==================== 类型 ====================
export interface FsFile {
  id: number | string
  fileName: string
  storageKey: string
  storageType: string
  contentType: string | null
  fileSize: number
  bizType: string | null
  bizId: string | null
  contentMd5?: string | null
  /** 分片直传进行中非空（complete 后清空） */
  uploadId?: string | null
  createTime: string
}

export interface StorageInfo {
  /** 当前存储模式：local / rustfs */
  type: string
  /** 预签名直链是否可用（rustfs 模式且开关开启） */
  presignEnabled: boolean
  /** 分片直传是否可用（rustfs 模式） */
  multipartEnabled: boolean
}

/** 单个分片的预签名直传地址 */
export interface MultipartPartUrl {
  partNumber: number
  url: string
}

export interface MultipartInitResult {
  fileId: string
  partSize: number
  parts: MultipartPartUrl[]
}

/** 可生成缩略图的图片类型（与后端 FsFileService 白名单一致） */
export function isThumbnailSupported(file: Pick<FsFile, 'contentType'>) {
  return ['image/jpeg', 'image/png', 'image/gif', 'image/bmp'].includes((file.contentType || '').toLowerCase())
}

// ==================== API ====================
export function pageFiles(params: Record<string, unknown>) {
  return request.get('/fs/file/page', { params })
}

export function uploadFile(file: File, bizType?: string, bizId?: string) {
  const form = new FormData()
  form.append('file', file)
  if (bizType) form.append('bizType', bizType)
  if (bizId) form.append('bizId', bizId)
  return request.post('/fs/file/upload', form, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export function deleteFile(id: number | string) {
  return request.delete(`/fs/file/${id}`)
}

export function presignUrl(id: number | string) {
  return request.get(`/fs/file/${id}/presign-url`)
}

export function storageInfo() {
  return request.get('/fs/file/storage-info')
}

/** 存储迁移（同步执行，历史对象量大时耗时较长；timeout 0 = 不限） */
export function migrateStorage(from: string) {
  return request.post('/fs/storage/migrate', null, { params: { from }, timeout: 0 })
}

// ==================== 缩略图 ====================
// img 标签带不了 Authorization 头，统一经 axios（blob）拉取后转 objectURL
export async function fetchThumbnailUrl(id: number | string, width = 256): Promise<string> {
  const response = await request.get(`/fs/file/${id}/thumbnail`, {
    params: { width },
    responseType: 'blob',
    timeout: 30000
  })
  return URL.createObjectURL(response.data as Blob)
}

// ==================== 分片直传（rustfs 模式；浏览器 PUT 预签名 URL） ====================
export function initMultipart(file: File) {
  return request.post('/fs/file/multipart/init', {
    fileName: file.name,
    contentType: file.type || 'application/octet-stream',
    size: file.size
  })
}

export function completeMultipart(fileId: string, parts: { partNumber: number; etag: string }[]) {
  return request.post(`/fs/file/multipart/${fileId}/complete`, { parts })
}

export function abortMultipart(fileId: string) {
  return request.post(`/fs/file/multipart/${fileId}/abort`)
}

/**
 * 分片直传整文件：init 拿全部分片预签名 URL → 并发 3 片 PUT → 收集 ETag → complete 组装。
 * 直连对象存储端点（跨域），要求存储侧 CORS 允许来源且 expose ETag 响应头。
 */
export async function uploadMultipartFile(file: File, onProgress?: (percent: number) => void) {
  const init: MultipartInitResult = await initMultipart(file)
  const etags: { partNumber: number; etag: string }[] = []
  let uploaded = 0
  const queue = [...init.parts]
  const worker = async () => {
    while (queue.length) {
      const part = queue.shift()!
      const start = (part.partNumber - 1) * init.partSize
      const chunk = file.slice(start, Math.min(start + init.partSize, file.size))
      const response = await fetch(part.url, { method: 'PUT', body: chunk })
      if (!response.ok) {
        throw new Error(`分片 ${part.partNumber} 直传失败：HTTP ${response.status}`)
      }
      const etag = response.headers.get('ETag') || response.headers.get('etag')
      if (!etag) {
        throw new Error('未读到分片 ETag：请检查对象存储 CORS 配置（Access-Control-Expose-Headers: ETag）')
      }
      etags.push({ partNumber: part.partNumber, etag })
      uploaded++
      onProgress?.(Math.round((uploaded * 100) / init.parts.length))
    }
  }
  await Promise.all(Array.from({ length: Math.min(3, queue.length) }, worker))
  try {
    await completeMultipart(init.fileId, etags)
  } catch (e) {
    // 组装失败：放弃会话并清理已登记的元数据行，避免残留「进行中」垃圾行
    await abortMultipart(init.fileId).catch(() => {})
    throw e
  }
}

// ==================== 下载 ====================
/** 下载（blob 流式；触发浏览器保存） */
export async function downloadFile(file: FsFile) {
  const response = await request.get(`/fs/file/${file.id}/download`, { responseType: 'blob', timeout: 0 })
  const blob = new Blob([response.data], {
    type: file.contentType || 'application/octet-stream'
  })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = file.fileName
  a.click()
  URL.revokeObjectURL(url)
}
