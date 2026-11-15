<template>
  <div class="composer">
    <!-- 顶边拖拽手柄：上拖加高 / 下拖收窄（微信式高输入区），双击恢复默认 -->
    <div class="resize-handle" title="拖拽调整输入区高度（双击恢复默认）"
         @mousedown.stop.prevent="onResizeStart" @dblclick="onResizeDblClick"></div>

    <!-- 引用条 -->
    <div v-if="quoteMsg" class="quote-bar">
      <span class="qb-text">回复 {{ quoteMsg.senderNickname || quoteMsg.sendID }}：{{ quoteSummary }}</span>
      <button class="qb-x" title="取消引用" @click="$emit('update:quoteMsg', null)">×</button>
    </div>

    <!-- 输入区：高度手动可调（默认加高），内容超出时内部滚动 -->
    <div class="input-row" :style="{ height: editorH + 'px' }">
      <div class="input-wrap">
        <AtPicker :visible="atVisible" :members="members" :keyword="atKeyword" :me-id="meId"
                  @pick="pickAt" @pick-all="pickAtAll" />
        <textarea ref="inputEl" v-model="text" placeholder="输入消息… Enter 发送 / Shift+Enter 换行"
                  @keydown.enter="onEnter" @input="onInput" @click="maybeAt" @keyup="maybeAt"
                  @focus="onFocus" @blur="onBlur" @compositionstart="composing = true" @compositionend="composing = false"></textarea>
      </div>
    </div>

    <!-- 表情面板 -->
    <EmojiPanel v-if="panel === 'emoji'" class="panel-pos" @pick="pickEmoji" />

    <!-- 语音录制条 -->
    <div v-if="panel === 'voice'" class="voice-bar">
      <template v-if="!recording">
        <span class="hint">点击开始录音（最长 60 秒）</span>
        <button class="primary sm" @click="startVoice">开始录音</button>
        <button class="sm" @click="panel = ''">取消</button>
      </template>
      <template v-else>
        <span class="rec-dot"></span>
        <span class="hint">录音中 {{ voiceSec }}s</span>
        <button class="sm" @click="cancelVoice">取消</button>
        <button class="primary sm" @click="stopVoice(false)">发送</button>
      </template>
    </div>

    <!-- 底部工具栏 + 发送（微信式布局：输入区在上，工具条在下） -->
    <div class="bottom-bar">
      <button class="tool" title="表情" @click.stop="togglePanel('emoji')">😊</button>
      <button class="tool" title="图片" @click="sendImage">🖼</button>
      <button class="tool" title="视频" @click="sendVideo">🎬</button>
      <button class="tool" title="文件" @click="sendFile">📎</button>
      <button class="tool" :title="recording ? '录音中' : '语音'" @click.stop="recording ? cancelVoice() : togglePanel('voice')">
        {{ recording ? '⏺' : '🎤' }}</button>
      <button class="tool" title="名片" @click="$emit('pick-card')">🪪</button>
      <button class="tool" title="链接卡片" @click="$emit('compose-link')">🔗</button>
      <span class="tool-spacer"></span>
      <button class="tool" title="语音通话" :disabled="!rtcEnabled || !canCall" @click="$emit('call', 'audio')">📞</button>
      <button class="tool" title="视频通话" :disabled="!rtcEnabled || !canCall" @click="$emit('call', 'video')">🎥</button>
      <button class="primary send-btn" :disabled="!text.trim()" @click="doSend">发送</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import EmojiPanel from './EmojiPanel.vue'
import AtPicker from './AtPicker.vue'
import { VoiceRecorder, pickAnyFile, pickImage, pickVideo } from './media'
import { summaryOf } from './summary'

const props = defineProps<{
  meId: string
  /** 群聊成员（@ 候选）；单聊为空数组 */
  members: any[]
  isGroup: boolean
  quoteMsg: any | null
  rtcEnabled: boolean
  /** 单聊才有发起通话按钮 */
  canCall: boolean
  draft: string
}>()

const emit = defineEmits<{
  'update:draft': [v: string]
  'update:quoteMsg': [v: any | null]
  'send-text': [p: { text: string; atUserList: string[]; atUsersInfo: any[]; atAll: boolean; quote: any | null }]
  'send-image': [p: any]
  'send-video': [p: any]
  'send-file': [file: File]
  'send-sound': [p: { file: File; duration: number }]
  'pick-card': []
  'compose-link': []
  call: [media: 'audio' | 'video']
  'typing-focus': []
  'typing-blur': []
}>()

const inputEl = ref<HTMLTextAreaElement>()
const text = ref('')
const composing = ref(false)
const panel = ref<'' | 'emoji' | 'voice'>('')
const recording = ref(false)
const voiceSec = ref(0)
const recorder = new VoiceRecorder()

/* @ 提及状态机：输入 @ 后字符作为过滤词，选人后替换为 @昵称 并记录 atUserList */
const atVisible = ref(false)
const atKeyword = ref('')
const atStart = ref(-1)
const atUsers = ref<any[]>([])   // 本次草稿已 @ 的成员（非全员）
const atAllFlag = ref(false)

const quoteSummary = computed(() => (props.quoteMsg ? summaryOf(props.quoteMsg) : ''))

watch(() => props.draft, v => { if (v !== text.value) text.value = v || '' })
watch(() => props.quoteMsg, () => { nextTick(() => inputEl.value?.focus()) })

/* ---------- 输入区高度（默认加高 + 顶边拖拽手动调，localStorage 记忆；双击恢复默认） ---------- */

const COMPOSER_H_KEY = 'im-composer-height'
const COMPOSER_H_DEFAULT = 150
const COMPOSER_H_MIN = 96
const COMPOSER_H_MAX = 420

const editorH = ref(loadHeight())

function loadHeight(): number {
  try {
    const v = Number(localStorage.getItem(COMPOSER_H_KEY))
    if (Number.isFinite(v) && v >= COMPOSER_H_MIN && v <= COMPOSER_H_MAX) return v
  } catch { /* 忽略 */ }
  return COMPOSER_H_DEFAULT
}

let resizeStartY = 0
let resizeStartH = 0

function onResizeStart(e: MouseEvent) {
  resizeStartY = e.clientY
  resizeStartH = editorH.value
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onResizeMove)
  window.addEventListener('mouseup', onResizeEnd)
}

function onResizeMove(e: MouseEvent) {
  // 手柄上移（dy 为负）= 输入区加高
  const h = resizeStartH - (e.clientY - resizeStartY)
  editorH.value = Math.max(COMPOSER_H_MIN, Math.min(COMPOSER_H_MAX, h))
}

function onResizeEnd() {
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onResizeMove)
  window.removeEventListener('mouseup', onResizeEnd)
  try { localStorage.setItem(COMPOSER_H_KEY, String(editorH.value)) } catch { /* 忽略 */ }
}

function onResizeDblClick() {
  editorH.value = COMPOSER_H_DEFAULT
  try { localStorage.setItem(COMPOSER_H_KEY, String(COMPOSER_H_DEFAULT)) } catch { /* 忽略 */ }
}

function togglePanel(p: 'emoji' | 'voice') {
  panel.value = panel.value === p ? '' : p
  if (panel.value === '') return
  if (p === 'voice' && !recorder.supported) { panel.value = ''; alert('当前浏览器不支持录音'); }
}

function pickEmoji(e: string) {
  const el = inputEl.value
  if (el && el.selectionStart != null) {
    const s = el.selectionStart, epos = el.selectionEnd ?? s
    text.value = text.value.slice(0, s) + e + text.value.slice(epos)
    nextTick(() => { el.focus(); el.selectionStart = el.selectionEnd = s + e.length })
  } else {
    text.value += e
  }
  emit('update:draft', text.value)
  panel.value = ''
}

function onInput() {
  emit('update:draft', text.value)
  maybeAt()
  // 草稿去抖由 App 侧 watch 处理；这里直接保存也行（本地状态无成本）
}

function maybeAt() {
  const el = inputEl.value
  if (!el || !props.isGroup) return
  const pos = el.selectionStart ?? -1
  const before = text.value.slice(0, pos)
  const m = before.match(/(^|\s)@([^\s@]*)$/)
  if (m) {
    atVisible.value = true
    atKeyword.value = m[2] || ''
    atStart.value = pos - (m[2]?.length || 0) - 1
  } else if (atVisible.value) {
    atVisible.value = false
  }
}

function insertAtToken(token: string) {
  const el = inputEl.value
  if (!el) return
  const pos = el.selectionStart ?? text.value.length
  const before = text.value.slice(0, atStart.value >= 0 ? atStart.value : pos)
  const after = text.value.slice(pos)
  text.value = `${before}${token} ${after}`
  atVisible.value = false
  emit('update:draft', text.value)
  nextTick(() => { el.focus(); const p = (atStart.value >= 0 ? atStart.value : pos) + token.length + 1; el.selectionStart = el.selectionEnd = p })
}

function pickAt(m: any) {
  atUsers.value = [...atUsers.value.filter(u => u.atUserID !== m.userID), { atUserID: m.userID, groupNickname: m.nickname || m.userID }]
  insertAtToken('@' + (m.nickname || m.userID))
}

function pickAtAll() {
  atAllFlag.value = true
  insertAtToken('@所有人')
}

function onEnter(e: KeyboardEvent) {
  if (composing.value || e.shiftKey || e.isComposing) return
  e.preventDefault()
  doSend()
}

function onFocus() {
  emit('typing-focus')
}

function onBlur() {
  atVisible.value = false
  emit('typing-blur')
}

function doSend() {
  const t = text.value.trim()
  if (!t) return
  emit('send-text', {
    text: text.value,
    atUserList: atUsers.value.map(u => u.atUserID),
    atUsersInfo: atUsers.value,
    atAll: atAllFlag.value,
    quote: props.quoteMsg,
  })
  text.value = ''
  atUsers.value = []
  atAllFlag.value = false
  atVisible.value = false
  emit('update:draft', '')
  emit('update:quoteMsg', null)
}

/* ---------- 媒体入口 ---------- */

async function sendImage() {
  const p = await pickImage()
  if (p) emit('send-image', p)
}

async function sendVideo() {
  const p = await pickVideo()
  if (p) emit('send-video', p)
}

async function sendFile() {
  const f = await pickAnyFile()
  if (f) emit('send-file', f)
}

async function startVoice() {
  try {
    await recorder.start(sec => { voiceSec.value = sec }, 60, r => {
      // 到达 60 秒上限自动停止：直接发送
      recording.value = false
      voiceSec.value = 0
      panel.value = ''
      if (r) emit('send-sound', r)
    })
    recording.value = true
  } catch (e: any) {
    recording.value = false
    alert(e?.message || '录音失败')
  }
}

async function stopVoice(discard: boolean) {
  const r = await recorder.stop(discard)
  recording.value = false
  voiceSec.value = 0
  panel.value = ''
  if (r) emit('send-sound', r)
}

function cancelVoice() {
  if (recording.value) { stopVoice(true); return }
  panel.value = ''
}

onBeforeUnmount(() => {
  recorder.stop(true)
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onResizeMove)
  window.removeEventListener('mouseup', onResizeEnd)
})
</script>

<style scoped>
.composer { flex: none; border-top: 1px solid var(--im-border); background: var(--im-panel-bg); position: relative; }
.resize-handle { position: relative; height: 6px; flex: none; cursor: ns-resize; }
.resize-handle::after {
  content: ''; position: absolute; left: 50%; top: 2px; transform: translateX(-50%);
  width: 36px; height: 2px; border-radius: 1px; background: transparent; transition: background .15s;
}
.resize-handle:hover::after { background: var(--im-border); }
.quote-bar { display: flex; align-items: center; gap: 8px; margin: 2px 14px 0; padding: 5px 10px; background: var(--im-hover); border-left: 3px solid var(--im-primary); border-radius: 6px; font-size: 12px; color: var(--im-text-2); }
.qb-text { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.qb-x { border: none; background: transparent; cursor: pointer; color: var(--im-text-2); font-size: 14px; padding: 0 2px; }
.input-row { display: flex; padding: 2px 14px 0; }
.input-wrap { flex: 1; min-width: 0; height: 100%; position: relative; }
textarea {
  width: 100%; height: 100%; resize: none; border: none; outline: none;
  background: transparent; color: var(--im-text);
  font-size: 14px; line-height: 1.5; padding: 6px 10px; font-family: inherit; overflow-y: auto;
}
.bottom-bar { display: flex; align-items: center; gap: 2px; padding: 4px 12px 10px; }
.tool { border: none; background: transparent; font-size: 17px; padding: 4px 7px; border-radius: 6px; cursor: pointer; line-height: 1; }
.tool:hover:not(:disabled) { background: var(--im-hover); }
.tool:disabled { opacity: .4; cursor: default; }
.tool-spacer { flex: 1; }
.panel-pos { position: absolute; bottom: calc(100% - 4px); left: 12px; z-index: 25; }
.voice-bar { display: flex; align-items: center; gap: 10px; padding: 8px 14px; }
.rec-dot { width: 10px; height: 10px; border-radius: 50%; background: #e34d59; animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: .2; } }
.send-btn { flex: none; margin-left: 10px; padding: 6px 20px; }
.sm { padding: 5px 12px; font-size: 12px; }
.hint { color: var(--im-text-2); font-size: 12px; }
</style>
