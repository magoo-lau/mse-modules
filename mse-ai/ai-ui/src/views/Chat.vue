<template>
  <div class="chat-page">
    <!-- 会话侧栏 -->
    <div class="session-panel">
      <el-select v-model="currentAgent" placeholder="选择智能体" style="width: 100%" @change="onAgentChange">
        <el-option v-for="a in agents" :key="a.agentCode" :label="a.name" :value="a.agentCode">
          <span class="agent-option">
            <agent-avatar :avatar="a.avatar" :name="a.name" :size="18" />
            <span>{{ a.name }}</span>
          </span>
        </el-option>
      </el-select>
      <el-button type="primary" plain style="width: 100%; margin-top: 8px" @click="newSession">新对话</el-button>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: s.id === sessionId }"
          @click="openSession(s)"
        >
          <span class="session-title">{{ s.title }}</span>
          <span class="session-ops" @click.stop>
            <el-dropdown trigger="click" @command="(cmd: string) => onSessionCommand(cmd, s)">
              <span class="ops-trigger">⋯</span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="rename">重命名</el-dropdown-item>
                  <el-dropdown-item command="delete">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </span>
        </div>
        <el-empty v-if="!sessions.length" description="暂无会话" :image-size="60" />
      </div>
    </div>

    <!-- 消息窗 -->
    <div class="chat-panel">
      <div ref="messageBox" class="message-box">
        <el-empty v-if="!messages.length && !streaming" :description="welcome" />
        <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role">
          <div class="msg-col">
            <div v-if="m.toolRunning" class="tool-running">🔍 正在调用 {{ m.toolRunning }}…</div>
            <div
              v-if="m.role === 'assistant' && !m.pending"
              class="msg-bubble md"
              v-html="renderMd(m.content)"
            ></div>
            <div v-else-if="m.content" class="msg-bubble">
              {{ m.content }}<span v-if="m.role === 'assistant' && m.pending" class="cursor">▌</span>
            </div>
            <DatasetCard v-for="(d, di) in m.datasets" :key="di" :dataset="d" />
            <ModelInputsPanel v-if="canTrace && m.rounds?.length" :rounds="m.rounds" />
            <ToolTraceList v-if="canTrace && m.toolCalls?.length" :steps="m.toolCalls" />
            <div v-if="m.toolNotice" class="tool-notice">
              ⚠️ 本次未调用绑定的工具（{{ m.toolNotice.boundTools?.join('、') || '无' }}）——{{
                m.toolNotice.resolved
                  ? '模型端点可能不支持 function calling，请检查或更换绑定模型'
                  : '绑定的工具不可用，请检查工具登记与部署形态'
              }}
            </div>
            <el-collapse v-if="m.sources?.length" class="source-panel">
              <el-collapse-item :title="`📎 引用来源（${m.sources.length}）`" name="sources">
                <div v-for="s in m.sources" :key="s.index" class="source-item">
                  <div class="source-meta">
                    [{{ s.index }}] 📄 {{ s.fileName || '未知文档' }}
                    <el-tag size="small" effect="plain">{{ (s.score * 100).toFixed(0) }}%</el-tag>
                  </div>
                  <div class="source-excerpt">{{ s.excerpt }}</div>
                </div>
              </el-collapse-item>
            </el-collapse>
            <el-collapse v-if="m.trace?.length" class="source-panel">
              <el-collapse-item :title="`🧩 执行过程（${m.trace.length} 个节点）`" name="trace">
                <div v-for="t in m.trace" :key="t.nodeId" class="trace-item" :class="t.status">
                  <span class="trace-icon">
                    {{ t.status === 'success' ? '✓' : t.status === 'failed' ? '✕' : t.status === 'cancelled' ? '⊘' : '◌' }}
                  </span>
                  <span>{{ t.title }}</span>
                  <el-tag v-if="t.nodeType" size="small" effect="plain" style="margin-left: 6px">{{ t.nodeType }}</el-tag>
                  <span v-if="t.elapsedMs != null" class="trace-ms">{{ t.elapsedMs }}ms</span>
                  <div v-if="t.output" class="trace-output">{{ t.output }}</div>
                  <div v-if="t.error" class="trace-error">{{ t.error }}</div>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
      </div>
      <div v-if="usageTip" class="usage-tip">{{ usageTip }}</div>
      <div class="input-bar">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入消息，Enter 发送 / Shift+Enter 换行"
          :disabled="streaming"
          @keydown.enter.exact.prevent="send"
        />
        <el-button v-if="!streaming" type="primary" @click="send">发送</el-button>
        <el-button v-else type="warning" plain @click="stopStream">停止</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import MarkdownIt from 'markdown-it'
import { agentApi, chatApi } from '../api/ai'
import type {
  AiAgent, AiChatSession, ChatRoundInput, ChatSource, ChatTurnSnapshot,
  DatasetPayload, FlowNodeTrace, ModelMessageSnapshot, ToolCallStep, ToolEvent
} from '../api/ai'
import { useAuthStore } from '../stores/auth'
import { applyToolEvent } from '../utils/toolTrace'
import AgentAvatar from '../components/AgentAvatar.vue'
import DatasetCard from '../components/DatasetCard.vue'
import ModelInputsPanel from '../components/ModelInputsPanel.vue'
import ToolTraceList from '../components/ToolTraceList.vue'

// markdown 渲染（html:false 防注入；仅 assistant 完成后渲染，流式期间保持纯文本）
const md = new MarkdownIt({ html: false, linkify: true })
function renderMd(text: string): string {
  return md.render(text || '')
}

const authStore = useAuthStore()
/** 工具调用轨迹可见性（ai:chat:trace；后端已按权限 withheld tool 事件与 ext.toolCalls，此处双保险） */
const canTrace = computed(() => authStore.hasPermission('ai:chat:trace'))

interface ChatMsg {
  role: 'user' | 'assistant'
  content: string
  pending?: boolean
  sources?: ChatSource[]
  trace?: FlowNodeTrace[]
  datasets?: DatasetPayload[]
  /** 工具调用逐步轨迹（SSE tool 事件按 seq 聚合 / ext.toolCalls 历史回放；ai:chat:trace 权限者） */
  toolCalls?: ToolCallStep[]
  /** 每轮模型实际输入快照（SSE turn 事件实时构建 / ext.turns 历史回放；ai:chat:trace 权限者） */
  rounds?: ChatRoundInput[]
  /** 正在执行的工具名（tool 事件 start → result 之间显示） */
  toolRunning?: string
  /** 工具未调用告警（unused 事件） */
  toolNotice?: { resolved: boolean; boundTools?: string[] }
}

/** 确保某轮输入块存在（turn 事件实时构建；messages 非空时覆盖回填） */
function ensureRound(msg: ChatMsg, index: number, messages?: ModelMessageSnapshot[]): ChatRoundInput {
  if (!msg.rounds) msg.rounds = []
  let round = msg.rounds.find((r) => r.index === index)
  if (!round) {
    round = { index, elapsedMs: null, messages: messages ? [...messages] : [] }
    msg.rounds.push(round)
    msg.rounds.sort((a, b) => a.index - b.index)
  } else if (messages?.length) {
    round.messages = messages
  }
  return round
}

/** ext.turns → 轮次输入（历史回放：最后一轮输入取前一轮的 next） */
function roundsFromExt(turns: ChatTurnSnapshot[]): ChatRoundInput[] {
  const rounds: ChatRoundInput[] = []
  for (const t of turns || []) {
    if (t.index == null) continue
    rounds.push({ index: t.index, elapsedMs: t.elapsedMs ?? null, messages: t.messages || [] })
  }
  const last = rounds.length ? turns[turns.length - 1] : null
  if (last?.next?.length && !rounds.some((r) => r.index === last.index + 1)) {
    rounds.push({ index: last.index + 1, elapsedMs: null, messages: last.next || [] })
  }
  return rounds.sort((a, b) => a.index - b.index)
}

const agents = ref<AiAgent[]>([])
const currentAgent = ref('')
const sessions = ref<AiChatSession[]>([])
const sessionId = ref('')
const messages = ref<ChatMsg[]>([])
const draft = ref('')
const streaming = ref(false)
const usageTip = ref('')
const messageBox = ref<HTMLElement>()

const currentAgentObj = computed(() => agents.value.find((a) => a.agentCode === currentAgent.value))
const welcome = computed(
  () => currentAgentObj.value?.welcomeMessage || '选择一个智能体，开始对话'
)

onMounted(async () => {
  agents.value = (await agentApi.visible()) as any
  if (agents.value.length) currentAgent.value = agents.value[0].agentCode
  await refreshSessions()
})

async function refreshSessions() {
  const page = pageData(await chatApi.sessions({ pageNum: 1, pageSize: 100 }))
  sessions.value = page
}

function pageData(body: any): any {
  return body?.records ?? []
}

function onAgentChange() {
  sessionId.value = ''
  messages.value = []
}

function newSession() {
  sessionId.value = ''
  messages.value = []
  usageTip.value = ''
  draft.value = ''
  ElMessage.success('已开启新对话')
}

async function openSession(s: AiChatSession) {
  sessionId.value = s.id
  currentAgent.value = s.agentCode
  let history: any[]
  try {
    history = (await chatApi.messages(s.id)) as any
  } catch {
    // 加载失败保留当前态并可见化，避免静默空态误导「记录丢了」
    ElMessage.error('加载会话记录失败')
    return
  }
  messages.value = history.map((m: any) => {
    const msg: ChatMsg = { role: m.role, content: m.content }
    // ext 扩展段：恢复数据结果卡片、引用来源与工具调用轨迹（随消息落库；toolCalls 仅权限者下发）
    if (m.ext) {
      try {
        const ext = JSON.parse(m.ext)
        if (ext.datasets?.length) msg.datasets = ext.datasets
        if (ext.sources?.length) msg.sources = ext.sources
        if (ext.toolCalls?.length) msg.toolCalls = ext.toolCalls
        if (ext.turns?.length) msg.rounds = roundsFromExt(ext.turns)
      } catch {
        /* 非法 ext 忽略 */
      }
    }
    return msg
  })
  scrollBottom()
}

async function onSessionCommand(cmd: string, s: AiChatSession) {
  if (cmd === 'rename') {
    const { value } = await ElMessageBox.prompt('新的会话标题', '重命名', { inputValue: s.title })
    await chatApi.rename(s.id, value)
    await refreshSessions()
  } else if (cmd === 'delete') {
    await ElMessageBox.confirm('删除该会话及全部消息？', '确认', { type: 'warning' })
    await chatApi.remove(s.id)
    if (s.id === sessionId.value) newSession()
    await refreshSessions()
  }
}

/** 当前流式请求的取消句柄（停止按钮断开 SSE，服务端随之终止推送） */
let streamController: AbortController | null = null

async function send() {
  const text = draft.value.trim()
  if (!text || streaming.value) return
  if (!currentAgent.value) {
    ElMessage.warning('请先选择智能体')
    return
  }
  draft.value = ''
  usageTip.value = ''
  messages.value.push({ role: 'user', content: text })
  // reactive 包装：流式增量/工具状态均直接变异该对象，必须经代理才能触发渲染
  const assistant = reactive<ChatMsg>({ role: 'assistant', content: '', pending: true })
  messages.value.push(assistant)
  streaming.value = true
  streamController = new AbortController()
  scrollBottom()

  try {
    await chatApi.stream(
      { agentCode: currentAgent.value, message: text, conversationId: sessionId.value || undefined },
      {
        onSources: (sources) => {
          assistant.sources = sources
          scrollBottom()
        },
        onTurn: (e) => {
          // 模型轮次边界（仅 ai:chat:trace 权限者收到）：构建每轮模型输入面板
          if (!canTrace.value) return
          if (e.phase === 'turn') {
            ensureRound(assistant, e.index, e.messages).elapsedMs = e.elapsedMs ?? null
            ensureRound(assistant, e.index + 1)
          } else {
            // turn-end：出口历史 = 下一轮输入快照（最后一轮无自身边界，由它补齐）
            const next = ensureRound(assistant, e.index + 1, e.messages)
            if (!next.messages.length) next.messages = e.messages || []
          }
          scrollBottom()
        },
        onTool: (e: ToolEvent) => {
          // 模型轮次边界（phase=turn/turn-end）已由 onTurn 消费；空结果终止引导（nodata）
          // 仅调试台展示，工作台防御性忽略——后端改写已生效，无需用户侧提示
          if (e.phase === 'turn' || e.phase === 'turn-end' || e.phase === 'nodata') return
          if (e.phase === 'start') {
            assistant.toolRunning = e.tool || '工具'
          } else if (e.phase === 'result') {
            assistant.toolRunning = undefined
            if (e.dataset) {
              if (!assistant.datasets) assistant.datasets = []
              assistant.datasets.push(e.dataset)
            }
          } else if (e.phase === 'unused') {
            // 仅在回复疑似「表演执行」（输出代码围栏或 <tool_call> 工具调用标记却没调工具）时提示，避免闲聊类无需工具的回复误报
            if (assistant.content.includes('```') || /<\/tool_call>|<function=/.test(assistant.content)) {
              assistant.toolNotice = { resolved: !!e.resolved, boundTools: e.boundTools }
            }
          }
          // start/result/limit 聚合为逐步轨迹（后端按权限推送，canTrace 双保险）
          if (e.phase !== 'unused' && canTrace.value) {
            if (!assistant.toolCalls) assistant.toolCalls = []
            applyToolEvent(assistant.toolCalls, e)
          }
          scrollBottom()
        },
        onNode: (t) => {
          if (!assistant.trace) assistant.trace = []
          const existing = assistant.trace.find((x) => x.nodeId === t.nodeId)
          if (existing) Object.assign(existing, t)
          else assistant.trace.push(t)
          scrollBottom()
        },
        onMessage: (delta) => {
          assistant.content += delta
          scrollBottom()
        },
        onUsage: (u) => {
          usageTip.value = `tokens：输入 ${u.promptTokens} / 输出 ${u.completionTokens} / 合计 ${u.totalTokens}`
        },
        onDone: (d) => {
          if (!sessionId.value && d?.sessionId) {
            sessionId.value = d.sessionId
            refreshSessions()
          }
        },
        onError: (msg) => {
          if (!assistant.content) assistant.content = `（失败：${msg}）`
          ElMessage.error(msg)
        }
      },
      { signal: streamController.signal }
    )
  } catch (e: any) {
    ElMessage.error(e?.message || '网络异常')
  } finally {
    assistant.pending = false
    if (streamController?.signal.aborted && !assistant.content) {
      assistant.content = '（已停止）'
    }
    streaming.value = false
    streamController = null
    scrollBottom()
  }
}

/** 停止当前流式回复：断开 SSE，并通知服务端取消（编排智能体跨实例广播取消信号；未在运行幂等返回） */
function stopStream() {
  streamController?.abort()
  if (sessionId.value) {
    chatApi.stopStream(sessionId.value).catch(() => {})
  }
}

function scrollBottom() {
  nextTick(() => {
    messageBox.value?.scrollTo({ top: messageBox.value.scrollHeight })
  })
}
</script>

<style scoped>
.chat-page {
  display: flex;
  /* 全高页面统一走 100% 高度链（wujie 下 100vh 是主应用视口，含基座导航/页签会撑破容器） */
  height: 100%;
}
.session-panel {
  width: 240px;
  border-right: 1px solid var(--el-border-color-light);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.session-list {
  flex: 1;
  overflow: auto;
  margin-top: 8px;
}
.session-item {
  display: flex;
  align-items: center;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.session-item:hover {
  background: var(--el-fill-color-light);
}
.session-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.session-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-ops {
  color: var(--el-text-color-secondary);
}
.chat-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.message-box {
  flex: 1;
  overflow: auto;
  padding: 16px 24px;
}
.msg-row {
  display: flex;
  margin-bottom: 10px;
}
.msg-row.user {
  justify-content: flex-end;
}
.msg-bubble {
  max-width: 72%;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--el-fill-color);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
}
.msg-row.user .msg-bubble {
  background: var(--el-color-primary);
  color: #fff;
}
.tool-running {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 2px 0;
}
.tool-notice {
  font-size: 12px;
  color: var(--el-color-warning);
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 4px 8px;
  line-height: 1.6;
}
/* markdown 渲染的消息体：恢复常规空白处理，补代码块/表格基础样式（全部走 EP 变量适配深浅色） */
.msg-bubble.md {
  white-space: normal;
}
.msg-bubble.md :deep(p) {
  margin: 0 0 6px;
}
.msg-bubble.md :deep(p:last-child) {
  margin-bottom: 0;
}
.msg-bubble.md :deep(ul),
.msg-bubble.md :deep(ol) {
  margin: 0 0 6px;
  padding-left: 18px;
}
.msg-bubble.md :deep(pre) {
  margin: 6px 0;
  padding: 8px;
  border-radius: 4px;
  background: var(--el-fill-color-dark);
  overflow: auto;
}
.msg-bubble.md :deep(code) {
  font-family: inherit;
  font-size: 12px;
}
.msg-bubble.md :deep(table) {
  border-collapse: collapse;
  margin: 6px 0;
  font-size: 12px;
}
.msg-bubble.md :deep(th),
.msg-bubble.md :deep(td) {
  border: 1px solid var(--el-border-color-lighter);
  padding: 4px 8px;
}
.msg-col {
  max-width: 72%;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.msg-col .msg-bubble {
  max-width: 100%;
}
.source-panel {
  border: none;
  font-size: 12px;
}
.source-panel :deep(.el-collapse-item__header) {
  color: var(--el-text-color-secondary);
  height: 28px;
  line-height: 28px;
}
.source-panel :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}
.source-panel :deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}
.source-item {
  padding: 6px 8px;
  margin-bottom: 4px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.source-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
  margin-bottom: 2px;
}
.trace-item {
  font-size: 12px;
  padding: 2px 0;
  color: var(--el-text-color-primary);
}
.trace-icon {
  margin-right: 4px;
}
.trace-item.success .trace-icon {
  color: var(--el-color-success);
}
.trace-item.failed .trace-icon {
  color: var(--el-color-danger);
}
.trace-item.cancelled .trace-icon {
  color: var(--el-text-color-secondary);
}
.trace-ms {
  margin-left: 6px;
  color: var(--el-text-color-secondary);
}
.trace-output {
  margin: 2px 0 0 18px;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 80px;
  overflow: auto;
}
.trace-error {
  margin: 2px 0 0 18px;
  color: var(--el-color-danger);
  white-space: pre-wrap;
}
.source-excerpt {
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 100px;
  overflow: auto;
}
.cursor {
  animation: blink 1s infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
.usage-tip {
  text-align: right;
  padding: 0 24px 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.input-bar {
  display: flex;
  gap: 8px;
  padding: 8px 16px 12px;
  border-top: 1px solid var(--el-border-color-light);
}
.agent-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
