<template>
  <div v-if="visible" class="mp-mask" @click.self="$emit('close')">
    <div class="mp">
      <div class="mp-head">
        <b>聊天记录</b>
        <button class="x" @click="$emit('close')">×</button>
      </div>
      <div class="mp-body">
        <template v-for="m in messages" :key="m.clientMsgID">
          <div class="mp-day" v-if="dayChanged(m)">{{ fmtDay(m.sendTime) }}</div>
          <div class="mp-msg" :class="{ mine: m.sendID === meId }">
            <img v-if="m.senderFaceUrl" class="face" :src="assetUrl(m.senderFaceUrl)" alt="" />
            <span v-else class="face char" :class="{ bot: isBotUserId(m.sendID) }">{{ isBotUserId(m.sendID) ? '🤖' : (m.senderNickname || m.sendID || '?').charAt(0) }}</span>
            <div class="mp-body">
              <div class="mp-meta"><span>{{ m.senderNickname || m.sendID }}</span><span>{{ fmtTime(m.sendTime) }}</span></div>
              <component :is="bubbleOf(m)" v-bind="propsOf(m)" />
            </div>
          </div>
        </template>
        <div v-if="!messages.length" class="mp-empty">消息内容为空</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import ImageBubble from './bubbles/ImageBubble.vue'
import VideoBubble from './bubbles/VideoBubble.vue'
import VoiceBubble from './bubbles/VoiceBubble.vue'
import FileBubble from './bubbles/FileBubble.vue'
import CardBubble from './bubbles/CardBubble.vue'
import LocationBubble from './bubbles/LocationBubble.vue'
import MergeBubble from './bubbles/MergeBubble.vue'
import LinkCardBubble from './bubbles/LinkCardBubble.vue'
import AppShareBubble from './bubbles/AppShareBubble.vue'
import QuoteBubble from './bubbles/QuoteBubble.vue'
import TextBubble from './bubbles/TextBubble.vue'
import { isAppShare, isLinkCard, parseCustom } from './custom'
import { assetUrl, atTextDisplay, fmtDay, fmtTime, isBotUserId } from './util'

const props = defineProps<{ visible: boolean; messages: any[]; meId: string }>()
defineEmits<{ close: [] }>()

let lastDay = ''
function dayChanged(m: any) {
  const d = new Date(m.sendTime || 0).toDateString()
  const changed = d !== lastDay
  lastDay = d
  return changed
}

function bubbleOf(m: any) {
  switch (m.contentType) {
    case 102: return ImageBubble
    case 103: return VoiceBubble
    case 104: return VideoBubble
    case 105: return FileBubble
    case 108: return CardBubble
    case 109: return LocationBubble
    case 114: return QuoteBubble
    case 107: return MergeBubble
    case 110: {
      const v = parseCustom(m.customElem?.data)
      if (isAppShare(v)) return AppShareBubble
      return isLinkCard(v) ? LinkCardBubble : TextBubble
    }
    default: return TextBubble
  }
}

function propsOf(m: any) {
  switch (m.contentType) {
    case 101: return { text: m.textElem?.content || '' }
    case 106: return { text: atTextDisplay(m.atTextElem?.text), atUsers: m.atTextElem?.atUsersInfo }
    case 102: return { elem: m.pictureElem }
    case 103: return { elem: m.soundElem }
    case 104: return { elem: m.videoElem }
    case 105: return { elem: m.fileElem }
    case 108: return { elem: m.cardElem }
    case 109: return { elem: m.locationElem }
    case 114: return { elem: m.quoteElem }
    case 107: return { elem: m.mergeElem }
    case 110: {
      const v = parseCustom(m.customElem?.data)
      if (isAppShare(v)) return { card: v }
      return isLinkCard(v) ? { card: v } : { text: m.customElem?.data || '' }
    }
    default: return { text: m.content || '[不支持的消息]' }
  }
}
</script>

<style scoped>
.mp-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .4); z-index: 80; display: flex; align-items: center; justify-content: center; }
.mp { width: 480px; max-width: 94vw; height: 70vh; background: var(--im-panel-bg); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 8px 32px var(--im-shadow); }
.mp-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid var(--im-border); }
.mp-head .x { border: none; background: transparent; font-size: 20px; color: var(--im-text-2); cursor: pointer; }
.mp-body { flex: 1; overflow: auto; padding: 14px 16px; display: flex; flex-direction: column; gap: 10px; }
.mp-day { align-self: center; font-size: 11px; color: var(--im-text-2); }
.mp-msg { display: flex; gap: 8px; max-width: 80%; }
.mp-msg.mine { align-self: flex-end; flex-direction: row-reverse; }
.mp-body { min-width: 0; display: flex; flex-direction: column; gap: 3px; align-items: flex-start; }
.mp-msg.mine .mp-body { align-items: flex-end; }
.mp-meta { display: flex; gap: 6px; font-size: 11px; color: var(--im-text-2); }
.face { width: 28px; height: 28px; border-radius: 6px; flex: none; object-fit: cover; }
.face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 13px; }
.mp-empty { color: var(--im-text-2); font-size: 12px; text-align: center; margin-top: 30px; }
</style>
