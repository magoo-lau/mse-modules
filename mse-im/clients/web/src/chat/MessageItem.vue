<template>
  <!-- 撤回/系统通知行 -->
  <div v-if="isRevoked" class="sys-row">{{ sysText }}</div>
  <div v-else-if="isNotice" class="sys-row">{{ noticeText }}</div>

  <div v-else class="msg" :class="{ mine }" :data-msg-id="m.clientMsgID">
    <label v-if="selecting" class="sel-box" @click.stop="$emit('toggle-select')">
      <input type="checkbox" :checked="selected" />
    </label>
    <img v-if="m.senderFaceUrl" class="face sm click" :src="assetUrl(m.senderFaceUrl)" alt="" @click.stop="$emit('show-profile', m.sendID)" />
    <span v-else class="face sm char click" :class="{ bot: isBotSender }" @click.stop="$emit('show-profile', m.sendID)">{{ isBotSender ? '🤖' : (m.senderNickname || m.sendID || '?').charAt(0) }}</span>
    <div class="msg-body" @contextmenu.prevent="openMenu">
      <div class="msg-meta"><span>{{ m.senderNickname || m.sendID }}</span><span>{{ fmtTime(m.sendTime) }}</span></div>
      <div class="bubble-wrap">
        <span v-if="mine && m.status === 1" class="st st-sending" title="发送中">···</span>
        <span v-else-if="mine && m.status === 3" class="st st-failed" title="点击重发" @click="$emit('resend')">!</span>
        <component :is="bubbleComp" v-bind="bubbleProps" @view="$emit('view', $event)" @play="$emit('play', $event)"
                   @preview="$emit('preview')" @locate="$emit('locate')" @open="$emit('open-card')" />
        <button class="bubble-more" title="更多操作" @click.stop="openMenu">⋯</button>
      </div>
      <span v-if="showRead" class="read-state" :class="{ read: m.isRead }">{{ m.isRead ? '已读' : '未读' }}</span>
      <div v-if="uploading" class="upload-bar"><i :style="{ width: progress + '%' }"></i></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import TextBubble from './bubbles/TextBubble.vue'
import ImageBubble from './bubbles/ImageBubble.vue'
import VideoBubble from './bubbles/VideoBubble.vue'
import VoiceBubble from './bubbles/VoiceBubble.vue'
import FileBubble from './bubbles/FileBubble.vue'
import CardBubble from './bubbles/CardBubble.vue'
import LinkCardBubble from './bubbles/LinkCardBubble.vue'
import AppShareBubble from './bubbles/AppShareBubble.vue'
import QuoteBubble from './bubbles/QuoteBubble.vue'
import MergeBubble from './bubbles/MergeBubble.vue'
import LocationBubble from './bubbles/LocationBubble.vue'
import { isAppShare, isLinkCard, parseCustom } from './custom'
import { assetUrl, atTextDisplay, fmtTime, isBotUserId } from './util'

const props = defineProps<{
  m: any
  meId: string
  /** 上传进度（clientMsgID → 0-100），仅媒体发送中展示 */
  progress?: number
  selecting?: boolean
  selected?: boolean
}>()

const emit = defineEmits<{
  menu: [payload: { x: number; y: number; msg: any }]
  view: [url: string]
  play: [url: string]
  preview: []
  locate: []
  'open-card': []
  resend: []
  'toggle-select': []
  /** 点击发送者头像：查看资料卡（ userID，bot_ 前缀由上层分流机器人资料） */
  'show-profile': [userId: string]
}>()

const mine = computed(() => props.m.sendID === props.meId)
/** 机器人发送者：未配置头像时兜底绿底 🤖（与通讯录/资料卡一致），不走昵称首字 */
const isBotSender = computed(() => isBotUserId(props.m.sendID))
const uploading = computed(() => mine.value && props.m.status === 1 && props.progress != null && props.progress < 100)
const progress = computed(() => Math.max(0, Math.min(100, props.progress ?? 0)))

/** 单聊且自己发的已入库消息展示已读回执 */
const showRead = computed(() =>
  mine.value && props.m.sessionType === 1 && props.m.seq > 0 && (props.m.contentType === 101 || props.m.contentType === 106))

/** 撤回通知（2101）：content 为 AdvancedRevokeContent JSON */
const isRevoked = computed(() => props.m.contentType === 2101)
const sysText = computed(() => {
  let c: any = {}
  try { c = JSON.parse(props.m.content || '{}') } catch { /* 非 JSON 按通用文案 */ }
  if (c.revokerID && c.revokerID === props.meId) return '你撤回了一条消息'
  const who = c.revokerNickname || c.sourceMessageSenderNickname || ''
  return who ? `${who} 撤回了一条消息` : '撤回了一条消息'
})
const isNotice = computed(() => props.m.contentType >= 1500 && props.m.contentType < 1600)
const noticeText = computed(() => {
  try {
    const d = JSON.parse(props.m.content || '{}')
    return d.detail || '群通知'
  } catch { return '群通知' }
})

/** 气泡分发：按 contentType 映射组件与 props */
const bubbleComp = computed(() => {
  switch (props.m.contentType) {
    case 101: case 106: return TextBubble
    case 102: return ImageBubble
    case 103: return VoiceBubble
    case 104: return VideoBubble
    case 105: return FileBubble
    case 108: return CardBubble
    case 109: return LocationBubble
    case 114: return QuoteBubble
    case 107: return MergeBubble
    case 110: {
      const v = parseCustom(props.m.customElem?.data)
      if (isAppShare(v)) return AppShareBubble
      return isLinkCard(v) ? LinkCardBubble : TextBubble
    }
    default: return TextBubble
  }
})

const bubbleProps = computed(() => {
  switch (props.m.contentType) {
    case 101: return { text: props.m.textElem?.content || '' }
    case 106: return { text: atTextDisplay(props.m.atTextElem?.text), atUsers: props.m.atTextElem?.atUsersInfo }
    case 102: return { elem: props.m.pictureElem }
    case 103: return { elem: props.m.soundElem }
    case 104: return { elem: props.m.videoElem }
    case 105: return { elem: props.m.fileElem }
    case 108: return { elem: props.m.cardElem }
    case 109: return { elem: props.m.locationElem }
    case 114: return { elem: props.m.quoteElem }
    case 107: return { elem: props.m.mergeElem }
    case 110: {
      const v = parseCustom(props.m.customElem?.data)
      if (isAppShare(v)) return { card: v }
      if (isLinkCard(v)) return { card: v }
      // 机器人回复等自由文本 custom
      return { text: props.m.customElem?.data || '' }
    }
    default: return { text: props.m.content || '[暂不支持的消息类型]' }
  }
})

function openMenu(e: MouseEvent) {
  emit('menu', { x: e.clientX, y: e.clientY, msg: props.m })
}
</script>

<style scoped>
.sys-row {
  align-self: center; font-size: 11px; color: var(--im-text-2);
  background: var(--im-hover); padding: 3px 10px; border-radius: 10px; max-width: 80%;
  text-align: center; word-break: break-all;
}
.msg { display: flex; gap: 10px; max-width: 72%; align-self: flex-start; }
.msg.mine { align-self: flex-end; flex-direction: row-reverse; }
.sel-box { align-self: center; flex: none; cursor: pointer; }
.msg-body { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 3px; }
.msg.mine .msg-body { align-items: flex-end; }
.msg-meta { display: flex; gap: 6px; font-size: 11px; color: var(--im-text-2); }
.bubble-wrap {
  display: flex; align-items: center; gap: 4px;
  background: var(--im-bubble-other); border: 1px solid var(--im-border); padding: 8px 10px;
  border-radius: 4px 12px 12px 12px; font-size: 14px; line-height: 1.5;
}
.msg.mine .bubble-wrap { background: var(--im-bubble-mine); border-radius: 12px 4px 12px 12px; }
.bubble-more {
  flex: none; border: none; background: transparent; color: var(--im-text-2); cursor: pointer;
  font-size: 13px; padding: 0 2px; opacity: 0; line-height: 1;
}
.bubble-wrap:hover .bubble-more { opacity: 1; }
.st { flex: none; font-size: 12px; line-height: 1; }
.st-failed { color: #e34d59; cursor: pointer; font-weight: 700; }
.st-sending { color: var(--im-text-2); }
.read-state { font-size: 10px; color: var(--im-text-2); }
.read-state.read { color: var(--im-primary); }
.upload-bar {
  width: 140px; height: 4px; border-radius: 2px; background: var(--im-hover); overflow: hidden;
}
.upload-bar i { display: block; height: 100%; background: var(--im-primary); transition: width .2s; }
</style>
