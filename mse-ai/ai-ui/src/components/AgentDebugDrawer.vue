<template>
  <el-drawer
    :model-value="visible"
    :title="`调试 - ${agent?.name || ''}`"
    size="100%"
    @update:model-value="$emit('update:visible', $event)"
  >
    <div class="drawer-content">
    <el-input v-model="query" type="textarea" :rows="3" resize="none"
              placeholder="输入调试问题（一次性试跑：不建会话、不带多轮记忆，按当前已保存配置执行）" />
    <el-input v-model="varsText" style="margin-top: 8px"
              placeholder='变量 JSON（可选，如 {"月份":"9月"}，渲染提示词 {{var}} 占位）' />
    <div class="debug-actions">
      <el-button type="primary" :loading="running" @click="runDebug">运行</el-button>
      <el-button v-if="running" type="danger" plain @click="stopRun">停止</el-button>
    </div>

    <template v-if="run">
      <!-- 汇总条 -->
      <div class="summary-bar">
        <el-tag :type="statusTag(run.status)" size="small">{{ statusLabel(run.status) }}</el-tag>
        <span class="summary-item">耗时 {{ displayElapsedMs }}ms</span>
        <span v-if="run.turns != null" class="summary-item">{{ run.turns }} 轮</span>
        <span v-if="run.toolCalls != null" class="summary-item">{{ run.toolCalls }} 次工具</span>
        <span v-if="run.usage" class="summary-item">
          tokens：输入 {{ run.usage.promptTokens ?? 0 }} / 输出 {{ run.usage.completionTokens ?? 0 }} / 合计 {{ run.usage.totalTokens ?? 0 }}
        </span>
      </div>
      <div v-if="run.unusedNotice" class="unused-notice">{{ run.unusedNotice }}</div>
      <div v-if="run.nodataNotices.length" class="nodata-notice">
        <div v-for="(n, i) in run.nodataNotices" :key="i">⚠️ {{ n.message }}</div>
      </div>
      <div v-if="run.error" class="run-error">{{ run.error }}</div>

      <!-- 过程时间线：每轮模型调用的输入/输出 + 工具调用 -->
      <div v-if="run.rounds.length" class="timeline">
        <div v-for="r in run.rounds" :key="r.index" class="round-card">
          <div class="round-head">
            <span class="round-title">第 {{ r.index }} 轮 · 模型调用</span>
            <span v-if="roundMs(r) != null" class="hint">模型 ≈{{ roundMs(r) }}ms</span>
            <span v-if="roundTokens(r)" class="hint">{{ roundTokens(r) }}</span>
            <el-tag v-if="r.steps.length" size="small" effect="plain" type="info" :disable-transitions="true">工具 ×{{ r.steps.length }}</el-tag>
            <el-tag v-if="isRunningRound(r)" size="small" effect="plain" type="warning" :disable-transitions="true">执行中</el-tag>
          </div>
          <el-collapse v-if="r.messages?.length" class="round-collapse">
            <el-collapse-item name="in">
              <template #title>
                <span class="collapse-title">📥 模型输入（{{ r.messages.length }} 条消息，实际发送的完整 prompt）</span>
              </template>
              <ModelMessagesViewer :messages="r.messages" />
            </el-collapse-item>
          </el-collapse>
          <template v-if="r.text">
            <div class="section-label">输出</div>
            <div class="round-text">{{ r.text }}</div>
          </template>
          <div v-else-if="r.steps.length || isRunningRound(r)" class="hint" style="margin-top: 6px">
            （本轮无文本输出，直接请求工具调用）
          </div>
          <ToolTraceList v-if="r.steps.length" :steps="r.steps" />
        </div>
      </div>

      <!-- 工具运行记录（跨轮次整合：完整入参/返回/数据集/重复计数，空数据高亮） -->
      <el-collapse v-if="allSteps.length" key="collapse-runs" class="extra-collapse">
        <el-collapse-item name="runs">
          <template #title>
            <span class="collapse-title">🛠 工具运行记录（{{ allSteps.length }} 次执行，按时间序整合）</span>
          </template>
          <ToolRunsPanel :steps="allSteps" />
        </el-collapse-item>
      </el-collapse>

      <!-- 回答（最后一轮模型输出） -->
      <template v-if="answer || !run.running">
        <div class="section-label">回答</div>
        <div v-if="answer" class="debug-answer">{{ answer }}</div>
        <div v-else-if="!run.running" class="hint">（无回答输出）</div>
      </template>

      <!-- 初始提示词（真实轮次快照缺失时的参照：系统提示词 + 变量渲染后的输入） -->
      <el-collapse v-if="run.promptPreview && !run.rounds.some((r) => r.messages?.length)" key="collapse-prompt" class="extra-collapse">
        <el-collapse-item name="prompt">
          <template #title>
            <span class="collapse-title">📋 初始提示词（系统提示词 + 渲染后输入）</span>
          </template>
          <ModelMessagesViewer :messages="promptPreviewMessages" />
        </el-collapse-item>
      </el-collapse>

      <!-- 数据卡片 -->
      <DatasetCard v-for="(d, i) in run.datasets" :key="`ds-${i}`" :dataset="d" />

      <!-- 引用来源 -->
      <el-collapse v-if="run.sources.length" key="collapse-sources" class="extra-collapse">
        <el-collapse-item name="sources">
          <template #title>
            <span class="collapse-title">📚 引用来源（{{ run.sources.length }}）</span>
          </template>
          <div v-for="s in run.sources" :key="s.index" class="source-item">
            <div class="source-head">
              <span class="source-name">[{{ s.index }}] {{ s.fileName }}</span>
              <span class="hint">score {{ s.score }}</span>
            </div>
            <div class="source-excerpt">{{ s.excerpt }}</div>
          </div>
        </el-collapse-item>
      </el-collapse>

      <!-- 原始事件（独立子组件：逐条格式化 + 点击展开 JSON + 类型统计 + 复制） -->
      <el-collapse v-if="run.rawEvents.length" key="collapse-raw" class="extra-collapse">
        <el-collapse-item name="raw">
          <template #title>
            <span class="collapse-title">🧾 原始事件（{{ run.rawEvents.length }} 条）</span>
          </template>
          <RawEventsPanel :events="run.rawEvents" />
        </el-collapse-item>
      </el-collapse>
    </template>
    <div v-else class="hint" style="margin-top: 16px">输入问题后点「运行」，全过程（每轮模型输入/输出、工具入参/返回、耗时、token）将在此展示。</div>

    <!-- 运行历史（抽屉内存，最近 ≤10 次） -->
    <el-collapse v-if="runs.length > 1" class="history-collapse">
      <el-collapse-item name="history">
        <template #title>
          <span class="collapse-title">🕘 运行历史（{{ runs.length }}）</span>
        </template>
        <div v-for="r in runs" :key="r.id" class="history-row" :class="{ active: r.id === activeRunId }"
             @click="activeRunId = r.id">
          <span class="history-time">{{ r.time }}</span>
          <span class="history-query">{{ r.query }}</span>
          <el-tag :type="statusTag(r.status)" size="small">{{ statusLabel(r.status) }}</el-tag>
          <span class="hint">{{ r.elapsedMs != null ? r.elapsedMs + 'ms' : '—' }}</span>
        </div>
      </el-collapse-item>
    </el-collapse>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi } from '../api/ai'
import type {
  AiAgent, ChatSource, DatasetPayload, DebugTurnEvent, ModelMessageSnapshot,
  PromptPreview, ToolCallStep, ToolEvent
} from '../api/ai'
import { applyToolEvent } from '../utils/toolTrace'
import DatasetCard from './DatasetCard.vue'
import ModelMessagesViewer from './ModelMessagesViewer.vue'
import RawEventsPanel from './RawEventsPanel.vue'
import type { RawEventEntry } from './RawEventsPanel'
import ToolRunsPanel from './ToolRunsPanel.vue'
import ToolTraceList from './ToolTraceList.vue'

/**
 * 简单配置智能体调试台 v2（对标 Dify 调试）：
 * 输入问题一次性试跑（不落会话/记忆），全过程时间线展示——
 * 每轮模型调用的真实输入消息（系统提示词/RAG 注入/工具回传）、输出文本、
 * 工具入参 + 返回内容 + 耗时 + 轮次、逐步与累计 token；另含运行历史（内存 ≤10 次）、
 * 原始事件查看与复制、运行中可停止。
 *
 * 数据源（SSE）：prompt（初始提示词）/ tool（start/result/limit/unused + turn/turn-end 轮次边界）/
 * message（增量文本）/ usage（累计 token）/ sources（引用来源）/ done（耗时/轮次/工具数）/ error。
 */
const props = defineProps<{ visible: boolean; agent: AiAgent | null }>()
defineEmits<{ (e: 'update:visible', v: boolean): void }>()

interface DebugRound {
  index: number
  /** 该轮模型实际输入消息快照（turn 事件；turn-end 事件补最后一轮） */
  messages: ModelMessageSnapshot[] | null
  /** 该轮模型输出文本（流式累积） */
  text: string
  /** 该轮工具调用轨迹 */
  steps: ToolCallStep[]
  /** 轮次边界时刻（自运行开始的 ms；估算每轮模型调用耗时用） */
  boundaryMs: number | null
  /** 截至该轮的累计 token 用量（相邻边界差分 = 每轮用量） */
  cumUsage: { promptTokens?: number; completionTokens?: number; totalTokens?: number } | null
}

interface DebugRun {
  id: number
  time: string
  query: string
  startedAt: number
  running: boolean
  status: 'running' | 'success' | 'failed' | 'stopped'
  rounds: DebugRound[]
  /** 当前文本归属轮（message delta 按「已见边界数 + 1」归组） */
  activeTextRound: number
  /** 当前工具归属轮（turn 边界后启动的工具属该边界轮） */
  activeToolRound: number
  promptPreview: PromptPreview | null
  datasets: DatasetPayload[]
  sources: ChatSource[]
  usage: { promptTokens?: number; completionTokens?: number; totalTokens?: number } | null
  elapsedMs: number | null
  turns: number | null
  toolCalls: number | null
  error: string | null
  unusedNotice: string | null
  /** 空结果终止引导提示（后端 phase=nodata 事件；重复空结果标 escalated） */
  nodataNotices: { tool: string; first: boolean; message: string }[]
  rawEvents: RawEventEntry[]
}

/** 原始事件条目（逐条格式化展示：时间 + 类型徽标 + 中文摘要，点击展开完整 JSON） */
interface RawEventEntry {
  time: string
  event: string
  data: any
  summary: string
  open?: boolean
}

const MAX_RUNS = 10
const MAX_RAW_EVENTS = 800

const query = ref('')
const varsText = ref('')
const runs = ref<DebugRun[]>([])
const activeRunId = ref<number | null>(null)
/** 心跳：运行中的耗时展示（500ms 刷新） */
const now = ref(Date.now())
setInterval(() => (now.value = Date.now()), 500)

let controller: AbortController | null = null
let runSeq = 0

const run = computed(() => runs.value.find((r) => r.id === activeRunId.value) || null)
const running = computed(() => runs.value.some((r) => r.running))

/** 最终回答 = 最后一轮模型输出 */
const answer = computed(() => {
  const list = run.value?.rounds || []
  return list.length ? list[list.length - 1].text : ''
})

/** 全部工具执行（跨轮次按时间序，工具运行记录面板用） */
const allSteps = computed<ToolCallStep[]>(() =>
  (run.value?.rounds || []).flatMap((r) => r.steps.map((s) => s))
)

const displayElapsedMs = computed(() => {
  const r = run.value
  if (!r) return 0
  return r.elapsedMs ?? Math.max(now.value - r.startedAt, 0)
})

/** 初始提示词 → 消息查看器形态（系统 + 用户两段） */
const promptPreviewMessages = computed<ModelMessageSnapshot[]>(() => {
  const p = run.value?.promptPreview
  if (!p) return []
  return [
    { role: 'system', content: p.system },
    { role: 'user', content: p.user }
  ]
})

// 切换智能体即清空调试历史（历史仅本次页面会话内存保留）
watch(
  () => props.agent?.id,
  () => {
    runs.value = []
    activeRunId.value = null
    query.value = ''
    varsText.value = ''
  }
)

function statusLabel(status: DebugRun['status']): string {
  return status === 'running' ? '执行中' : status === 'success' ? '成功' : status === 'stopped' ? '已停止' : '失败'
}

function statusTag(status: DebugRun['status']): 'primary' | 'success' | 'danger' | 'info' {
  return status === 'running' ? 'primary' : status === 'success' ? 'success' : status === 'stopped' ? 'info' : 'danger'
}

function ensureRound(target: DebugRun, index: number): DebugRound {
  let round = target.rounds.find((r) => r.index === index)
  if (!round) {
    round = { index, messages: null, text: '', steps: [], boundaryMs: null, cumUsage: null }
    target.rounds.push(round)
    target.rounds.sort((a, b) => a.index - b.index)
  }
  return round
}

function isRunningRound(r: DebugRound): boolean {
  return !!run.value?.running && run.value.activeTextRound === r.index
}

/** 每轮模型调用耗时估算：轮 N = 边界 N 时刻 - 边界 N-1 时刻；最后一轮 = 总耗时 - 最后边界 */
function roundMs(r: DebugRound): number | null {
  const target = run.value
  if (!target) return null
  if (r.boundaryMs != null) {
    const prev = target.rounds.find((x) => x.index === r.index - 1)?.boundaryMs ?? 0
    return Math.max(r.boundaryMs - prev, 0)
  }
  if (target.elapsedMs != null) {
    const lastBoundary = [...target.rounds].reverse().find((x) => x.boundaryMs != null)?.boundaryMs ?? 0
    return Math.max(target.elapsedMs - lastBoundary, 0)
  }
  return null
}

/** 每轮 token 估算：轮 N = 边界 N 累计 − 边界 N-1 累计；末轮（无自身边界）= 总量 − 最后边界累计 */
function roundTokens(r: DebugRound): string | null {
  const target = run.value
  if (!target) return null
  if (r.cumUsage) {
    const prev = target.rounds.find((x) => x.index === r.index - 1)?.cumUsage
    const p = (r.cumUsage.promptTokens ?? 0) - (prev?.promptTokens ?? 0)
    const c = (r.cumUsage.completionTokens ?? 0) - (prev?.completionTokens ?? 0)
    if (p > 0 || c > 0) return `输入≈${p} · 输出≈${c} tok`
  }
  if (target.usage) {
    const lastCum = [...target.rounds].reverse().find((x) => x.cumUsage)?.cumUsage
    const maxIndex = Math.max(...target.rounds.map((x) => x.index))
    if (!r.cumUsage && r.index === maxIndex && lastCum) {
      const p = (target.usage.promptTokens ?? 0) - (lastCum.promptTokens ?? 0)
      const c = (target.usage.completionTokens ?? 0) - (lastCum.completionTokens ?? 0)
      if (p > 0 || c > 0) return `输入≈${p} · 输出≈${c} tok`
    }
  }
  return null
}

/** 记录原始事件（连续 message 增量合并为单条累积事件，避免 delta 刷屏；格式化展示由 RawEventsPanel 负责） */
function pushRaw(target: DebugRun, event: string, data: unknown) {
  const last = target.rawEvents[target.rawEvents.length - 1]
  if (event === 'message' && last?.event === 'message') {
    // 首个 message 条目的 data 是 {delta} 形态，合并前先归一为累积结构
    if (typeof last.data?.text !== 'string') {
      last.data = { text: last.data?.delta || '', deltas: 1 }
    }
    last.data.text += (data as any)?.delta || ''
    last.data.deltas++
    return
  }
  if (target.rawEvents.length >= MAX_RAW_EVENTS) return
  target.rawEvents.push({ time: new Date().toLocaleTimeString(), event, data })
}

async function runDebug() {
  const target = props.agent
  const text = query.value.trim()
  if (!target || !text || running.value) return
  let inputs: Record<string, string> | undefined
  const vars = varsText.value.trim()
  if (vars) {
    try {
      inputs = JSON.parse(vars)
    } catch {
      ElMessage.warning('变量 JSON 格式不正确')
      return
    }
  }
  // reactive 包装：SSE 回调直接变异该对象（轮次/文本/耗时均就地更新），必须经代理才能触发渲染
  const record = reactive<DebugRun>({
    id: ++runSeq,
    time: new Date().toLocaleTimeString(),
    query: text,
    startedAt: Date.now(),
    running: true,
    status: 'running',
    rounds: [],
    activeTextRound: 1,
    activeToolRound: 0,
    promptPreview: null,
    datasets: [],
    sources: [],
    usage: null,
    elapsedMs: null,
    turns: null,
    toolCalls: null,
    error: null,
    unusedNotice: null,
    nodataNotices: [],
    rawEvents: []
  })
  runs.value.push(record)
  if (runs.value.length > MAX_RUNS) runs.value.shift()
  activeRunId.value = record.id
  controller = new AbortController()
  try {
    await agentApi.debugRun(
      target.id,
      { query: text, inputs },
      {
        onPrompt: (p) => {
          record.promptPreview = p
          pushRaw(record, 'prompt', p)
        },
        onTurn: (t) => onTurnEvent(record, t),
        onTool: (e) => onToolEvent(record, e),
        onMessage: (delta) => {
          pushRaw(record, 'message', { delta })
          ensureRound(record, record.activeTextRound).text += delta
        },
        onUsage: (u) => {
          record.usage = u
          pushRaw(record, 'usage', u)
        },
        onSources: (sources) => {
          record.sources = sources
          pushRaw(record, 'sources', sources)
        },
        onDone: (data) => {
          record.status = 'success'
          record.elapsedMs = data?.elapsedMs ?? Date.now() - record.startedAt
          record.turns = data?.turns ?? record.rounds.length
          record.toolCalls = data?.toolCalls ?? record.rounds.reduce((sum, r) => sum + r.steps.length, 0)
          pushRaw(record, 'done', data)
        },
        onError: (msg) => {
          record.status = 'failed'
          record.error = msg
          pushRaw(record, 'error', { message: msg })
          ElMessage.error(msg)
        }
      },
      { signal: controller.signal }
    )
  } finally {
    record.running = false
    if (record.status === 'running') record.status = 'success'
    controller = null
  }
}

function onTurnEvent(record: DebugRun, t: DebugTurnEvent) {
  pushRaw(record, 'tool', t)
  if (t.phase === 'turn') {
    // 边界 = 第 index 轮模型输出完毕并请求工具：登记该轮输入快照，
    // 后续工具事件归属本轮、文本增量归属下一轮
    const round = ensureRound(record, t.index)
    round.messages = t.messages || []
    round.boundaryMs = t.elapsedMs ?? null
    round.cumUsage = t.cumUsage || null
    record.activeToolRound = t.index
    record.activeTextRound = t.index + 1
    ensureRound(record, t.index + 1)
  } else {
    // turn-end：出口历史 = 下一轮输入快照（若下一轮再无边界，它就是最后一轮的输入）
    const next = ensureRound(record, t.index + 1)
    if (!next.messages?.length) next.messages = t.messages || []
  }
}

function onToolEvent(record: DebugRun, e: ToolEvent) {
  pushRaw(record, 'tool', e)
  if (e.phase === 'unused') {
    record.unusedNotice = e.resolved
      ? `绑定的工具（${(e.boundTools || []).join('、')}）模型一次都未调用——确认模型端点是否支持 function calling`
      : `绑定的工具（${(e.boundTools || []).join('、')}）均未解析成功——检查工具登记与部署形态`
    return
  }
  if (e.phase === 'nodata') {
    // 空结果终止引导（后端在「成功但无数据」的工具结果上追加/替换停止指令）
    record.nodataNotices.push({
      tool: e.tool || 'unknown',
      first: e.first !== false,
      message: e.message || ''
    })
    return
  }
  const round = ensureRound(record, Math.max(record.activeToolRound, 1))
  applyToolEvent(round.steps, e)
  if (e.phase === 'result' && e.dataset) {
    record.datasets.push(e.dataset)
  }
}

function stopRun() {
  const target = run.value
  if (!target?.running) return
  controller?.abort()
  target.status = 'stopped'
  target.running = false
  target.elapsedMs = Date.now() - target.startedAt
  target.error = '已手动停止'
}
</script>

<style scoped>
/* 全屏抽屉内容列宽约束（防全屏下行宽过长） */
.drawer-content {
  max-width: 1280px;
  margin: 0 auto;
}
.debug-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
.summary-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 10px;
  padding: 6px 10px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.summary-item {
  white-space: nowrap;
}
.unused-notice {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
  font-size: 12px;
}
.nodata-notice {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
  font-size: 12px;
  line-height: 1.8;
}
.run-error {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 12px;
}
.timeline {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.round-card {
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}
.round-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.round-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.round-collapse,
.extra-collapse,
.history-collapse {
  border: none;
  margin-top: 4px;
}
.round-collapse :deep(.el-collapse-item__header),
.extra-collapse :deep(.el-collapse-item__header),
.history-collapse :deep(.el-collapse-item__header) {
  height: 28px;
  line-height: 28px;
  background: transparent;
  border: none;
}
.round-collapse :deep(.el-collapse-item__wrap),
.extra-collapse :deep(.el-collapse-item__wrap),
.history-collapse :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}
.collapse-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.section-label {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.round-text {
  margin-top: 4px;
  padding: 6px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow: auto;
}
.debug-answer {
  margin-top: 4px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.source-item {
  padding: 4px 0;
}
.source-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.source-name {
  font-size: 12px;
  font-weight: 600;
}
.source-excerpt {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.history-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
.history-row:hover {
  background: var(--el-fill-color-light);
}
.history-row.active {
  background: var(--el-color-primary-light-9);
}
.history-time {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.history-query {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
