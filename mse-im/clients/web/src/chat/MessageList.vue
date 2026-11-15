<template>
  <div class="msgs" ref="listEl" @scroll="onScroll">
    <!-- 顶部加载更早 -->
    <div v-if="hasMore" class="load-more">
      <button v-if="loadingMore" class="hint-btn" disabled>加载中…</button>
      <button v-else class="hint-btn" @click="$emit('load-more')">查看更早消息</button>
    </div>
    <div v-else-if="messages.length" class="load-end">没有更多了</div>

    <template v-for="row in rows" :key="row.key">
      <div v-if="row.kind === 'day'" class="day-sep">{{ fmtDay(row.time) }}</div>
      <MessageItem v-else :m="row.msg" :me-id="meId" :progress="progressOf(row.msg)"
                   :selecting="selecting" :selected="selectedIds.includes(row.msg.clientMsgID)"
                   @menu="(p: any) => $emit('menu', p)" @view="(u: string) => $emit('view', u)"
                   @play="(u: string) => $emit('play', u)" @preview="(m: any) => $emit('preview', row.msg)"
                   @locate="(m: any) => $emit('locate', row.msg)" @open-card="(m: any) => $emit('open-card', row.msg)"
                   @resend="$emit('resend', row.msg)" @toggle-select="$emit('toggle-select', row.msg)"
                   @show-profile="(uid: string) => $emit('show-profile', uid)" />
    </template>
    <div v-if="!messages.length" class="empty-mini">暂无消息，打个招呼吧</div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import MessageItem from './MessageItem.vue'
import { fmtDay } from './util'

const props = defineProps<{
  messages: any[]
  meId: string
  hasMore?: boolean
  loadingMore?: boolean
  selecting?: boolean
  selectedIds: string[]
  progressMap: Record<string, number>
}>()

const emit = defineEmits<{
  'load-more': []
  menu: [p: { x: number; y: number; msg: any }]
  view: [url: string]
  play: [url: string]
  preview: [msg: any]
  locate: [msg: any]
  'open-card': [msg: any]
  resend: [msg: any]
  'toggle-select': [msg: any]
  'show-profile': [userId: string]
}>()

const listEl = ref<HTMLElement>()
/** 上滚加载时保持视觉位置 */
let anchorBottom = true
let prevHeight = 0

/** 消息行 + 日期分隔行的混合序列 */
type Row = { kind: 'day'; key: string; time: number } | { kind: 'msg'; key: string; msg: any }
const rows = computed<Row[]>(() => {
  const out: Row[] = []
  let lastDay = ''
  for (const m of props.messages) {
    if (!m?.clientMsgID) continue
    const day = new Date(m.sendTime || 0).toDateString()
    if (day !== lastDay) {
      lastDay = day
      out.push({ kind: 'day', key: 'd' + day, time: m.sendTime || 0 })
    }
    out.push({ kind: 'msg', key: m.clientMsgID, msg: m })
  }
  return out
})

function progressOf(m: any) { return props.progressMap[m.clientMsgID] }

function onScroll() {
  const el = listEl.value
  if (!el) return
  anchorBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  if (el.scrollTop <= 4 && props.hasMore && !props.loadingMore) emit('load-more')
}

function scrollToBottom() {
  nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight })
}

/** 定位到某条消息（引用跳转/搜索定位）：高亮闪一下 */
function scrollToMsg(clientMsgID: string) {
  nextTick(() => {
    const el = listEl.value?.querySelector(`[data-msg-id="${clientMsgID}"]`)
    if (el) {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' })
      el.classList.add('flash')
      setTimeout(() => el.classList.remove('flash'), 1600)
    }
  })
}

defineExpose({ scrollToBottom, scrollToMsg, isNearBottom: () => anchorBottom, listEl })

watch(() => props.messages.length, async (_n, old) => {
  const el = listEl.value
  if (!el) return
  // 上翻插入旧消息：维持滚动位置；新消息：贴近底部才自动跟随
  if (old != null && props.messages[0]?.clientMsgID && el.scrollTop < 40) {
    const delta = el.scrollHeight - prevHeight
    if (delta > 0) el.scrollTop += delta
  } else if (anchorBottom) {
    scrollToBottom()
  }
  prevHeight = el.scrollHeight
})
</script>

<style scoped>
.msgs { flex: 1; overflow-y: auto; padding: 16px 18px; display: flex; flex-direction: column; gap: 10px; }
.load-more { align-self: center; }
.hint-btn { border: none; background: transparent; color: var(--im-text-2); font-size: 12px; cursor: pointer; padding: 4px 10px; }
.hint-btn:hover:not(:disabled) { color: var(--im-primary); }
.load-end { align-self: center; font-size: 11px; color: var(--im-text-2); }
.day-sep { align-self: center; font-size: 11px; color: var(--im-text-2); }
.empty-mini { color: var(--im-text-2); font-size: 12px; text-align: center; margin-top: 30px; }
</style>
