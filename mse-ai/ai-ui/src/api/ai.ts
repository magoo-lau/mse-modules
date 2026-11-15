import axios from 'axios'
import request from './request'
import { useAuthStore } from '../stores/auth'

/** 文件下载（responseType=blob 需绕开 JSON 响应拦截器，独立用 axios 拉流并触发浏览器保存） */
async function downloadFile(url: string, fileName: string) {
  const token = useAuthStore().token
  const resp = await axios.get(`/api${url}`, {
    responseType: 'blob',
    timeout: 120000,
    headers: token ? { Authorization: token } : {}
  })
  const objectUrl = URL.createObjectURL(resp.data)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = fileName
  anchor.click()
  URL.revokeObjectURL(objectUrl)
}

// ==================== 基础类型 ====================
/** 模型类型：chat 对话模型（智能体/工作流绑定）/ embedding 向量模型（知识库 RAG 绑定）/ rerank 重排模型（知识库精排绑定） */
export type ModelType = 'chat' | 'embedding' | 'rerank'

export interface AiModel {
  id: string
  name: string
  provider: string
  protocol?: string
  /** 模型类型（chat / embedding / rerank，一行只表达一种能力） */
  modelType?: ModelType
  /** 默认向量模型标记（仅 modelType=embedding 有意义） */
  isDefault?: number
  baseUrl: string
  chatModel: string
  embeddingModel?: string
  embeddingBaseUrl?: string
  temperature?: number
  topP?: number
  maxTokens?: number
  /** 备用对话模型（ai_model.id；主模型瞬态错误重试仍失败时自动切换） */
  failoverModelId?: string
  status: number
  lastTestTime?: string
  lastTestOk?: number
  lastTestMsg?: string
  remark?: string
}

export interface AiAgent {
  id: string
  agentCode: string
  name: string
  avatar?: string
  description?: string
  systemPrompt?: string
  modelId?: string
  temperature?: number
  welcomeMessage?: string
  toolCodes?: string
  kbIds?: string
  /** 直出图表模式：0关 1开（一句话极简结论 + 图表中文列；声明智能体平台行随提供方刷新，租户副本可编辑） */
  chartDirect?: number
  /** 编排模式：simple 简单配置 / flow 画布编排 */
  orchestrationType?: string
  /** 画布编排 DSL（mse-flow/v1 JSON） */
  graphJson?: string
  scope?: string
  /** 来源：builtin 平台种子 / local 模块声明登记 / rpc 模块注册登记 / custom 页面自建 */
  source?: string
  /** 声明提供方服务名（source=rpc 时展示） */
  rpcGroup?: string
  status: number
  remark?: string
}

/** 工作流节点执行轨迹（SSE node 事件载荷） */
export interface FlowNodeTrace {
  nodeId: string
  nodeType: string
  title: string
  status: 'running' | 'success' | 'failed'
  elapsedMs?: number | null
  output?: string | null
  error?: string | null
}

/** 工具返回的结构化数据集（SSE tool 事件与消息 ext 落库共用） */
export interface DatasetPayload {
  /** 执行的 SQL（data_query execute 场景） */
  sql?: string | null
  columns: string[]
  rows: Record<string, any>[]
  rowCount: number
  hasMore: boolean
}

/** 工具执行轨迹（SSE tool 事件载荷） */
export interface ToolEvent {
  tool?: string
  phase: 'start' | 'result' | 'limit' | 'unused' | 'turn' | 'turn-end' | 'nodata'
  /** 步骤序号（start/result/limit 按 seq 配对；同名工具连环调用不串步） */
  seq?: number
  arguments?: any
  success?: boolean
  elapsedMs?: number
  /** phase=result 失败原因 / phase=limit 拦截说明 */
  error?: string
  message?: string
  /** phase=result 工具返回内容（截断留存，与审计同口径） */
  result?: string
  /** 所属模型轮次（1 起；0/缺省 = 旧数据未标注） */
  turn?: number
  dataset?: DatasetPayload
  /** phase=nodata：true=首次空结果（追加引导）/ false=重复空结果（硬终止） */
  first?: boolean
  /** phase=unused 时：true=工具可用但模型未调用（端点可能不支持 function calling）；false=工具未解析成功 */
  resolved?: boolean
  boundTools?: string[]
}

/** 模型输入消息快照（调试台轮次轨迹：该轮模型实际收到的完整 prompt） */
export interface ModelMessageSnapshot {
  role: string
  content?: string
  /** role=assistant 时模型请求的工具调用 */
  toolCalls?: { name: string; arguments: string }[]
  /** role=tool 时上一轮工具的真实回传（逐条） */
  responses?: { name: string; content: string }[]
}

/** 模型轮次边界（SSE tool 通道 phase=turn/turn-end，仅调试通道下发） */
export interface DebugTurnEvent {
  phase: 'turn' | 'turn-end'
  /** 轮次号（1 起）；phase=turn 时其 messages = 该轮模型实际输入快照 */
  index: number
  /** phase=turn：本次运行开始至该轮结束的毫秒数 */
  elapsedMs?: number
  /** 消息快照：turn=该轮输入；turn-end=下一轮输入 */
  messages?: ModelMessageSnapshot[]
  /** 截至该轮的累计 token 用量（相邻边界差分 = 每轮用量） */
  cumUsage?: { promptTokens?: number; completionTokens?: number; totalTokens?: number }
}

/** 初始提示词（SSE prompt 事件，仅调试通道：实际生效的系统提示词 + 变量渲染后的用户消息） */
export interface PromptPreview {
  system: string
  user: string
}

/**
 * 轮次输入快照（消息 ext.turns 落库形态，ai:chat:trace 权限者下发；上限 12 轮）：
 * 第 index 轮模型实际输入 = messages；最后一轮输入 = 前一轮的 next（无自身边界）。
 */
export interface ChatTurnSnapshot {
  index: number
  elapsedMs?: number
  messages?: ModelMessageSnapshot[]
  /** 本轮出口历史 = 下一轮输入快照 */
  next?: ModelMessageSnapshot[]
}

/** 工作台消息内联的轮次输入（实时 turn 事件构建 / ext.turns 回放共用形态） */
export interface ChatRoundInput {
  index: number
  elapsedMs?: number | null
  messages: ModelMessageSnapshot[]
}

/** 单次工具调用轨迹步骤（SSE 事件实时聚合 / 消息 ext.toolCalls 历史回放共用） */
export interface ToolCallStep {
  seq: number
  tool: string
  /** 入参 JSON 文本（截断留存） */
  arguments?: string | null
  /** running / success / fail / limit */
  status: string
  elapsedMs?: number | null
  error?: string | null
  /** 返回内容 JSON 文本（截断留存） */
  result?: string | null
  /** 所属模型轮次（1 起；0/缺省 = 旧数据未标注） */
  turn?: number | null
  dataset?: DatasetPayload | null
}

/** 编排画布图（mse-flow/v1） */
export interface FlowGraph {
  nodes: { id: string; type: string; title?: string; data?: any; position?: { x: number; y: number } }[]
  edges: { source: string; target: string; sourceHandle?: string | null }[]
  /** 全局参数：运行时以 env.<name> 预置，「变量赋值」节点可在执行期改写 */
  globals?: FlowGlobal[]
}

/** 画布全局参数（type：string/number/boolean/array/object，缺省 string 兼容旧图） */
export interface FlowGlobal {
  name: string
  value: string
  type?: string
  remark?: string
}

/** 数据查询节点数据源下拉选项 */
export interface DatasourceOption {
  id: number
  name: string
  type: string
}

/** DSL 导入结果（含不可映射警告清单） */
export interface DslImportResult {
  graph: FlowGraph
  warnings: string[]
}

export interface AiChatSession {
  id: string
  title: string
  agentCode: string
  lastActiveTime: string
}

export interface AiChatMessage {
  id: string
  sessionId: string
  role: string
  content: string
  /** 扩展信息 JSON（assistant 消息：datasets/sources/toolCalls/turns（每轮模型输入快照），历史回放恢复用；toolCalls 与 turns 仅 ai:chat:trace 权限者下发） */
  ext?: string
  createTime: string
}

export interface AiTool {
  id: string
  /** 归属租户：0=平台公共（系统工具），>0=租户自有 */
  tenantId?: number
  toolCode: string
  name?: string
  description?: string
  parametersJsonSchema?: string
  source: string
  rpcGroup?: string
  /** 外部工具连接配置（source=http/dify 时生效，JSON） */
  configJson?: string
  status: number
  remark?: string
}

export interface AiKnowledgeBase {
  id: string
  name: string
  /** 知识库编码（声明库壳通道：业务积木 AiKbProvider 声明 + 智能体 kbCodes 引用；手工库为空） */
  code?: string
  description?: string
  chunkSize?: number
  /** 切片方法：general 通用 / qa 问答对 / table 表格 / book 书籍 / one 整篇 */
  chunkMethod?: string
  chunkOverlap?: number
  topK?: number
  similarityThreshold?: number
  /** 绑定向量模型（ai_model.id；空=旧版全局配置/平台默认向量模型） */
  embeddingModelId?: string
  /** 绑定重排模型（ai_model.id；空=按旧版平台级配置解析，均未配置则不重排） */
  rerankModelId?: string
  /** 切片索引增强（LLM 自动关键词/自动问题）：0 关 1 开 */
  enhanceEnabled?: number
  /** 索引增强每切片自动问题数（1~5，默认 3） */
  enhanceQuestions?: number
  status: number
  remark?: string
}

export interface AiKbDocument {
  id: string
  kbId: string
  fileName: string
  fileType?: string
  sizeBytes?: number
  storagePath?: string
  status: number
  chunkCount?: number
  errorMsg?: string
}

/** 知识库切片 */
export interface AiKbChunk {
  id: string
  kbId: string
  documentId: string
  seq: number
  content: string
  /** 索引增强·自动关键词（JSON 数组串；空=未增强） */
  keywords?: string | null
  /** 索引增强·自动问题（JSON 数组串；空=未增强） */
  questions?: string | null
}

/** 检索命中（混合召回 + 重排后） */
export interface KbHit {
  chunkId: string
  docId: string
  fileName?: string
  score: number
  rerankScore?: number
  content: string
}

/** 检索测试结果（含实际生效参数与通道明细） */
export interface RetrievalResult {
  hits: KbHit[]
  filter?: string
  requestedTopK?: number
  reranked?: boolean
  fullText?: boolean
}

/** 对话引用来源（SSE sources 事件载荷） */
export interface ChatSource {
  index: number
  chunkId: string
  fileName: string
  score: number
  excerpt: string
}

export interface AiApiKey {
  id: string
  appId: string
  keyPrefix?: string
  tenantId: string | number
  agentCodes?: string
  ratePerMinute?: number
  status: number
  remark?: string
}

export interface AiMcpServer {
  id: string
  name: string
  endpoint: string
  ssePath?: string
  transport?: string
  status: number
  remark?: string
}

export interface PageData<T> {
  total: () => number
  pageNum: () => number
  pageSize: () => number
  records: () => T[]
}

// PageResult 为 record 序列化：{ total, pageNum, pageSize, records }
export function pageOf<T>(body: any): { total: number; records: T[] } {
  return { total: body?.total ?? 0, records: body?.records ?? [] }
}

// ==================== 模型管理（租户自管） ====================
export const modelApi = {
  page: (params: any) => request.get('/ai/model/page', { params }),
  get: (id: string) => request.get(`/ai/model/${id}`),
  save: (data: any) => request.post('/ai/model', data),
  remove: (id: string) => request.delete(`/ai/model/${id}`),
  test: (id: string) => request.post(`/ai/model/${id}/test`),
  available: (type?: 'chat' | 'embedding' | 'rerank') =>
    request.get('/ai/model/available', { params: type ? { type } : {} }),
  /** 设为本世界默认向量模型（仅向量模型行） */
  setDefaultEmbedding: (id: string) => request.post(`/ai/model/${id}/default-embedding`)
}

// ==================== 智能体 ====================
/**
 * 非 SSE 响应体统一报错：prepare 阶段的业务异常（如智能体未绑定对话模型）不经 SSE 返回，
 * 全局异常处理器产出 HTTP 200 + JSON 信封（{code,message}）——只判 response.ok 会把它当
 * 事件流解析，零事件静默结束表现为「发消息无回应」。此处读尽响应体按信封取 message。
 */
async function consumeErrorBody(response: Response, onError?: (message: string) => void): Promise<void> {
  let message = `HTTP ${response.status}`
  try {
    const text = await response.text()
    try {
      const body = JSON.parse(text)
      message = body.message || body.msg || text || message
    } catch {
      if (text) message = text
    }
  } catch {
    /* 读流失败保持 HTTP 状态兜底 */
  }
  onError?.(message)
}

/**
 * 试运行 SSE 通用流：fetch + ReadableStream 解析，事件 tool/node/message/prompt/usage/sources/done/error
 *（简单配置智能体调试走 tool 事件推送逐步轨迹与轮次边界 + prompt 初始提示词；画布编排走 node 节点轨迹）；
 * options.signal 支持主动停止（AbortController 断开后服务端随之终止推送）
 */
function streamDebugRun(
  url: string,
  body: { query: string; inputs?: Record<string, string> },
  handlers: {
    onTool?: (event: ToolEvent) => void
    onTurn?: (event: DebugTurnEvent) => void
    onNode?: (trace: FlowNodeTrace) => void
    onMessage?: (delta: string) => void
    onPrompt?: (preview: PromptPreview) => void
    onUsage?: (usage: any) => void
    onSources?: (sources: ChatSource[]) => void
    onDone?: (data: any) => void
    onError?: (message: string) => void
  },
  options?: { signal?: AbortSignal }
): Promise<void> {
  const token = useAuthStore().token
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token || '' },
    body: JSON.stringify(body),
    signal: options?.signal
  }).then(async (response) => {
    if (!response.ok || !response.body) {
      handlers.onError?.(`HTTP ${response.status}`)
      return
    }
    if (!(response.headers.get('content-type') || '').includes('text/event-stream')) {
      await consumeErrorBody(response, handlers.onError)
      return
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const events = buffer.split('\n\n')
      buffer = events.pop() || ''
      for (const event of events) {
        let name = 'message'
        const dataLines: string[] = []
        for (const line of event.split('\n')) {
          if (line.startsWith('event:')) name = line.slice(6).trim()
          else if (line.startsWith('data:')) dataLines.push(line.slice(5))
        }
        if (!dataLines.length) continue
        try {
          const data = JSON.parse(dataLines.join('\n'))
          if (name === 'tool') {
            // 轮次边界走 tool 通道但语义不同，单独分发（调试台时间线用）
            if (data.phase === 'turn' || data.phase === 'turn-end') handlers.onTurn?.(data)
            else handlers.onTool?.(data)
          } else if (name === 'node') handlers.onNode?.(data)
          else if (name === 'message') handlers.onMessage?.(data.delta || '')
          else if (name === 'prompt') handlers.onPrompt?.(data)
          else if (name === 'usage') handlers.onUsage?.(data)
          else if (name === 'sources') handlers.onSources?.(data.sources || [])
          else if (name === 'done') handlers.onDone?.(data)
          else if (name === 'error') handlers.onError?.(data.message || '试运行失败')
        } catch {
          /* 非 JSON 数据忽略 */
        }
      }
    }
  }).catch((e: any) => {
    // 主动停止（AbortController 触发）：静默收尾，不按网络错误处理
    if (e?.name !== 'AbortError') throw e
  })
}

export const agentApi = {
  visible: () => request.get('/ai/agent/visible'),
  /** 本世界智能体精简清单（开放能力白名单、用量页映射等下拉用；登录即可） */
  options: () => request.get('/ai/agent/options'),
  page: (params: any) => request.get('/ai/agent/page', { params }),
  get: (id: string) => request.get(`/ai/agent/${id}`),
  save: (data: any) => request.post('/ai/agent', data),
  remove: (id: string) => request.delete(`/ai/agent/${id}`),
  /** 头像上传（≤2MB png/jpg/jpeg/gif/webp），返回可写入 avatar 的站内地址 */
  uploadAgentAvatar: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request.post('/ai/agent/avatar', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },
  /** 发布：草稿编排图校验后拷贝为运行版并置已发布 */
  publish: (id: string) => request.post(`/ai/agent/${id}/publish`),
  /** 运行历史（本世界可见智能体的全部运行） */
  runPage: (params: any) => request.get('/ai/agent/run/page', { params }),
  runDetail: (id: string) => request.get(`/ai/agent/run/${id}`),
  /** 编排画布数据源下拉（数据查询节点：中台库 + 当前租户已启用数据源） */
  datasourceOptions: () => request.get('/ai/agent/datasource-options'),
  /** DSL 导入（source=mse 自有格式 / dify 尽力映射），返回画布图与警告清单 */
  dslImport: (id: string, content: string, source: string) =>
    request.post(`/ai/agent/${id}/dsl/import`, { content, source }),
  /** DSL 导出（mse-flow/v1 YAML 文本） */
  dslExport: (id: string) => request.get(`/ai/agent/${id}/dsl/export`),
  /** 智能体调试试运行（SSE；画布编排推 node 轨迹，简单配置推 prompt 初始提示词 + tool 逐步轨迹/轮次边界 + message） */
  debugRun(
    id: string,
    body: { query: string; inputs?: Record<string, string> },
    handlers: {
      onTool?: (event: ToolEvent) => void
      onTurn?: (event: DebugTurnEvent) => void
      onNode?: (trace: FlowNodeTrace) => void
      onMessage?: (delta: string) => void
      onPrompt?: (preview: PromptPreview) => void
      onUsage?: (usage: any) => void
      onSources?: (sources: ChatSource[]) => void
      onDone?: (data: any) => void
      onError?: (message: string) => void
    },
    options?: { signal?: AbortSignal }
  ): Promise<void> {
    return streamDebugRun(`/api/ai/agent/${id}/debug-run`, body, handlers, options)
  }
}

// ==================== 对话与会话 ====================
export const chatApi = {
  sessions: (params: any) => request.get('/ai/chat/session/page', { params }),
  messages: (sessionId: string) => request.get(`/ai/chat/session/${sessionId}/messages`),
  rename: (sessionId: string, title: string) => request.post(`/ai/chat/session/${sessionId}/title`, { title }),
  remove: (sessionId: string) => request.delete(`/ai/chat/session/${sessionId}`),
  /** SSE 流式对话：fetch + ReadableStream 解析 text/event-stream（POST 不能用 EventSource）；options.signal 支持主动停止 */
  stream(
    body: { agentCode: string; message: string; conversationId?: string },
    handlers: {
      onSources?: (sources: ChatSource[]) => void
      onTool?: (event: ToolEvent) => void
      onTurn?: (event: DebugTurnEvent) => void
      onMessage?: (delta: string) => void
      onNode?: (trace: FlowNodeTrace) => void
      onUsage?: (usage: any) => void
      onDone?: (data: any) => void
      onError?: (message: string) => void
    },
    options?: { signal?: AbortSignal }
  ): Promise<void> {
    const token = useAuthStore().token
    return fetch('/api/ai/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: token || '' },
      body: JSON.stringify(body),
      signal: options?.signal
    }).then(async (response) => {
      if (!response.ok || !response.body) {
        handlers.onError?.(`HTTP ${response.status}`)
        return
      }
      // prepare 阶段业务异常（未绑定模型等）以 HTTP 200 + JSON 信封返回，非 event-stream，按错误体解析
      if (!(response.headers.get('content-type') || '').includes('text/event-stream')) {
        await consumeErrorBody(response, handlers.onError)
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // SSE 事件以空行分隔
        const events = buffer.split('\n\n')
        buffer = events.pop() || ''
        for (const event of events) {
          let name = 'message'
          const dataLines: string[] = []
          for (const line of event.split('\n')) {
            if (line.startsWith('event:')) name = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5))
          }
          if (!dataLines.length) continue
          const raw = dataLines.join('\n')
          try {
            const data = JSON.parse(raw)
            if (name === 'message') handlers.onMessage?.(data.delta || '')
            else if (name === 'sources') handlers.onSources?.(data.sources || [])
            else if (name === 'tool') {
              // 模型轮次边界走 tool 通道但语义不同，单独分发（工作台轮次输入面板用）
              if (data.phase === 'turn' || data.phase === 'turn-end') handlers.onTurn?.(data)
              else handlers.onTool?.(data)
            } else if (name === 'node') handlers.onNode?.(data)
            else if (name === 'usage') handlers.onUsage?.(data)
            else if (name === 'done') handlers.onDone?.(data)
            else if (name === 'error') handlers.onError?.(data.message || '对话失败')
          } catch {
            /* 非 JSON 数据忽略 */
          }
        }
      }
    }).catch((e: any) => {
      // 主动停止（AbortController 触发）：静默收尾，不按网络错误处理
      if (e?.name !== 'AbortError') throw e
    })
  },
  /** 停止运行中的编排对话（服务端本地取消 + Redis 跨实例广播；简单模式/未在运行返回 false，幂等） */
  stopStream: (sessionId: string) => request.post('/ai/chat/stream/stop', { sessionId })
}

// ==================== 知识库 ====================
export const kbApi = {
  page: (params: any) => request.get('/ai/kb/page', { params }),
  get: (id: string) => request.get(`/ai/kb/${id}`),
  save: (data: any) => request.post('/ai/kb', data),
  remove: (id: string) => request.delete(`/ai/kb/${id}`),
  vectorStoreStatus: () => request.get('/ai/kb/vector-store-status'),
  documents: (id: string, params: any) => request.get(`/ai/kb/${id}/documents`, { params }),
  upload: (id: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    // 上传即返回（服务端异步解析），超时仅覆盖传输本身
    return request.post(`/ai/kb/${id}/documents`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000
    })
  },
  /** 重新解析：按当前知识库配置重切重嵌入 */
  reparse: (id: string, docId: string) => request.post(`/ai/kb/${id}/documents/${docId}/reparse`),
  /** 下载归档原文件（本地目录 / mse-fs 归档同端点） */
  downloadOriginal: async (id: string, docId: string, fileName: string) => {
    await downloadFile(`/ai/kb/${id}/documents/${docId}/file`, fileName)
  },
  /** 重建索引：全部文档重新解析 */
  rebuild: (id: string) => request.post(`/ai/kb/${id}/rebuild`),
  /** 重建索引（轻量）：不重解析文档，按现有切片跨集合清场后全量重嵌入（改绑向量模型后执行） */
  reindex: (id: string) => request.post(`/ai/kb/${id}/reindex`),
  removeDocument: (id: string, docId: string) => request.delete(`/ai/kb/${id}/documents/${docId}`),
  chunks: (id: string, docId: string, params: any) =>
    request.get(`/ai/kb/${id}/documents/${docId}/chunks`, { params }),
  addChunk: (id: string, docId: string, content: string) =>
    request.post(`/ai/kb/${id}/documents/${docId}/chunks`, { content }),
  updateChunk: (id: string, chunkId: string, content: string) =>
    request.put(`/ai/kb/${id}/chunks/${chunkId}`, { content }),
  removeChunk: (id: string, chunkId: string) => request.delete(`/ai/kb/${id}/chunks/${chunkId}`),
  search: (data: { kbId?: string; query: string; topK?: number; threshold?: number }) =>
    request.post('/ai/kb/search', data)
}

// ==================== 工具 ====================
/** 工具下拉选项（绑定选择所需：编码/名称/来源 + 说明与参数 Schema） */
export interface ToolOption {
  toolCode: string
  name?: string
  source: string
  description?: string
  parametersJsonSchema?: string
}

export const toolApi = {
  page: (params: any) => request.get('/ai/tool/page', { params }),
  local: () => request.get('/ai/tool/local'),
  /** 工具下拉选项（租户端/平台端智能体编辑共用；本地 Bean + 已启用登记工具 + MCP 工具） */
  options: () => request.get('/ai/tool/options'),
  save: (data: any) => request.post('/ai/tool', data),
  remove: (id: string) => request.delete(`/ai/tool/${id}`),
  test: (toolCode: string, argumentsJson: string) =>
    request.post(`/ai/tool/${toolCode}/test`, { arguments: argumentsJson }),
  logs: (params: any) => request.get('/ai/tool/log/page', { params })
}

// ==================== 平台：公共数据 ====================
/** 租户下拉选项（全局用量筛选等平台视角共用） */
export interface TenantOption {
  id: string | number
  code: string
  name: string
  status: number
}

export const platformApi = {
  tenantOptions: () => request.get('/ai/platform/tenant-options')
}

// ==================== 用量 ====================
export const usageApi = {
  page: (params: any) => request.get('/ai/usage/page', { params }),
  summary: (days = 14) => request.get('/ai/usage/summary', { params: { days } }),
  platformPage: (params: any) => request.get('/ai/usage/platform/page', { params }),
  platformSummary: (params: any) => request.get('/ai/usage/platform/summary', { params })
}

// ==================== 平台：MCP / API Key ====================
export const mcpApi = {
  page: (params: any) => request.get('/ai/mcp/page', { params }),
  save: (data: any) => request.post('/ai/mcp', data),
  remove: (id: string) => request.delete(`/ai/mcp/${id}`),
  test: (name: string) => request.post(`/ai/mcp/${name}/test`)
}

export const apiKeyApi = {
  page: (params: any) => request.get('/ai/apikey/page', { params }),
  create: (data: any) => request.post('/ai/apikey', data),
  status: (id: string, status: number) => request.post(`/ai/apikey/${id}/status`, { status }),
  remove: (id: string) => request.delete(`/ai/apikey/${id}`)
}
