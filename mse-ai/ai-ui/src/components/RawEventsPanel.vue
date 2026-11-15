<template>
  <div class="raw-panel">
    <div class="raw-head">
      <span class="hint">{{ stats }}</span>
      <el-button size="small" link type="primary" @click="copyAll">复制全部</el-button>
    </div>
    <div class="raw-list">
      <div v-for="(e, i) in events" :key="i" class="raw-item" :data-open="opened.has(i) ? '1' : null" @click="toggle(i)">
        <div class="raw-row">
          <span class="raw-time">{{ e.time }}</span>
          <el-tag size="small" effect="plain" :type="tagOf(label(e))" :disable-transitions="true">{{ label(e) }}</el-tag>
          <span class="raw-summary">{{ summary(e) }}</span>
        </div>
        <pre v-if="opened.has(i)" class="raw-json" @click.stop>{{ pretty(e.data) }}</pre>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
/** 原始事件条目（SSE 事件的结构化留存：时间 + 事件名 + 原始载荷） */
export interface RawEventEntry {
  time: string
  event: string
  data: any
}

export default { name: 'RawEventsPanel' }
</script>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { RawEventEntry } from './RawEventsPanel'

/**
 * 调试台「原始事件」面板（独立子组件：展开状态/复制/格式化自治）：
 * 逐条格式化渲染——时间 + 类型徽标 + 中文关键字段摘要，点击展开该事件完整 pretty JSON；
 * 面板头部展示事件类型统计；连续 message 增量已由调用方合并为累积条目。
 */
const props = defineProps<{ events: RawEventEntry[] }>()

/** 展开完整 JSON 的事件索引 */
const opened = ref(new Set<number>())

function toggle(index: number) {
  const next = new Set(opened.value)
  if (next.has(index)) {
    next.delete(index)
  } else {
    next.add(index)
  }
  opened.value = next
}

function truncateText(text: string, max: number): string {
  return text.length > max ? text.slice(0, max) + '…' : text
}

function compactJson(value: any, max = 60): string {
  try {
    return truncateText(JSON.stringify(value), max)
  } catch {
    return String(value)
  }
}

function pretty(value: any): string {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function messageSummary(d: { text: string; deltas: number }): string {
  const tail = d.text.length > 48 ? '…' + d.text.slice(-48) : d.text
  return `累计 ${d.text.length} 字（${d.deltas} 个增量）· ${tail.replace(/\n/g, '␤')}`
}

/** 轮次事件附带的累计 token 摘要后缀 */
function tokenSuffix(data: any): string {
  const cum = data?.cumUsage
  return cum ? ` · 累计 ${cum.totalTokens ?? 0} tok` : ''
}

function summary(entry: RawEventEntry): string {
  const event = entry.event
  const data = entry.data || {}
  try {
    if (event === 'prompt') {
      return `系统提示词 ${(data.system || '').length} 字 · 输入「${truncateText(data.user || '', 30)}」`
    }
    if (event === 'message') {
      return typeof data.text === 'string'
        ? messageSummary(data)
        : messageSummary({ text: data.delta || '', deltas: 1 })
    }
    if (event === 'usage') {
      return `输入 ${data.promptTokens} / 输出 ${data.completionTokens} / 合计 ${data.totalTokens} tokens`
    }
    if (event === 'sources') {
      const n = Array.isArray(data) ? data.length : data.sources?.length ?? 0
      return `${n} 条引用`
    }
    if (event === 'done') {
      return `${data?.elapsedMs ?? '?'}ms · ${data?.turns ?? '?'} 轮 · ${data?.toolCalls ?? '?'} 次工具`
    }
    if (event === 'error') {
      return data.message || '运行失败'
    }
    if (event === 'tool') {
      if (data.phase === 'turn') {
        return `第 ${data.index} 轮开始 · 模型输入 ${data.messages?.length ?? 0} 条消息${tokenSuffix(data)}`
      }
      if (data.phase === 'turn-end') {
        return `第 ${data.index} 轮结束 · 下一轮输入 ${data.messages?.length ?? 0} 条消息${tokenSuffix(data)}`
      }
      if (data.phase === 'start') {
        return `${data.tool} · 第 ${data.turn ?? 0} 轮 · 参数 ${compactJson(data.arguments)}`
      }
      if (data.phase === 'result') {
        return `${data.tool} · ${data.success ? '成功' : '失败'} · ${data.elapsedMs}ms`
          + (data.dataset ? ` · 数据集 ${data.dataset.rowCount} 行` : '')
          + (data.error ? ` · ${truncateText(data.error, 40)}` : '')
      }
      if (data.phase === 'limit') {
        return `${data.tool} 超限拦截 · ${truncateText(data.message || '', 40)}`
      }
      if (data.phase === 'nodata') {
        return `${data.tool} 空结果终止引导 · ${truncateText(data.message || '', 60)}`
      }
      if (data.phase === 'unused') {
        return `绑定工具未调用（${(data.boundTools || []).join('、')}）${data.resolved ? '· 模型未发起调用' : '· 工具未解析成功'}`
      }
    }
    return compactJson(data, 80)
  } catch {
    return ''
  }
}

/** 事件类型徽标文案 */
function label(entry: RawEventEntry): string {
  const event = entry.event
  const data = entry.data || {}
  if (event === 'prompt') return '提示词'
  if (event === 'message') return '文本'
  if (event === 'usage') return '用量'
  if (event === 'sources') return '来源'
  if (event === 'done') return '完成'
  if (event === 'error') return '错误'
  if (event === 'tool') {
    if (data.phase === 'turn' || data.phase === 'turn-end') return '轮次'
    if (data.phase === 'start' || data.phase === 'result') return '工具'
    if (data.phase === 'limit') return '拦截'
    if (data.phase === 'nodata') return '空结果'
    if (data.phase === 'unused') return '告警'
  }
  return event
}

function tagOf(labelText: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (labelText === '提示词' || labelText === '拦截' || labelText === '告警' || labelText === '空结果') return 'warning'
  if (labelText === '轮次') return 'primary'
  if (labelText === '工具' || labelText === '完成') return 'success'
  if (labelText === '错误') return 'danger'
  return 'info'
}

/** 事件统计（按类型徽标计数） */
const stats = computed(() => {
  const counts = new Map<string, number>()
  for (const e of props.events) {
    const labelText = label(e)
    counts.set(labelText, (counts.get(labelText) || 0) + 1)
  }
  return [...counts.entries()].map(([labelText, n]) => `${labelText} ${n}`).join(' · ')
})

async function copyAll() {
  const text = props.events
    .map((e) => `[${e.time}] ${label(e)} ${summary(e)}\n${pretty(e.data)}`)
    .join('\n\n')
  try {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(text)
    } else {
      fallbackCopy(text)
    }
    ElMessage.success('已复制')
  } catch {
    fallbackCopy(text)
    ElMessage.success('已复制')
  }
}

/** http 非 localhost 环境 navigator.clipboard 为 undefined，隐藏 textarea 兜底 */
function fallbackCopy(text: string) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  document.execCommand('copy')
  document.body.removeChild(ta)
}
</script>

<style scoped>
.raw-panel {
  display: flex;
  flex-direction: column;
}
.raw-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.raw-list {
  max-height: 420px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.raw-item {
  padding: 3px 6px;
  border-radius: 4px;
  cursor: pointer;
}
.raw-item:hover {
  background: var(--el-fill-color-light);
}
.raw-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.raw-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  font-family: monospace;
}
.raw-summary {
  flex: 1;
  font-size: 12px;
  color: var(--el-text-color-regular);
  word-break: break-all;
  line-height: 1.5;
}
.raw-json {
  margin: 4px 0 2px;
  padding: 6px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 260px;
  overflow: auto;
  cursor: default;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
