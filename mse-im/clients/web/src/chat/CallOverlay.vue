<template>
  <div v-if="call.state.phase !== 'idle'" class="co-mask">
    <div class="co" :class="{ video: call.state.media === 'video' && (call.state.phase === 'active' || call.state.phase === 'connecting') }">
      <!-- 视频画面层 -->
      <video v-if="call.state.media === 'video'" ref="remoteEl" class="co-remote" autoplay playsinline></video>
      <video v-if="call.state.media === 'video'" ref="localEl" class="co-local" autoplay playsinline muted></video>

      <div class="co-peer">
        <img v-if="call.state.peer?.faceURL" class="co-face" :src="assetUrl(call.state.peer.faceURL)" alt="" />
        <span v-else class="co-face char">{{ (call.state.peer?.nickname || '?').charAt(0) }}</span>
        <b class="co-name">{{ call.state.peer?.nickname || call.state.peer?.userID }}</b>
        <span class="co-status">{{ statusText }}</span>
        <span v-if="call.state.error" class="co-error">{{ call.state.error }}</span>
      </div>

      <div class="co-actions">
        <!-- 来电 -->
        <template v-if="call.state.phase === 'incoming'">
          <button class="co-btn danger" title="拒绝" @click="call.rejectCall()">✕</button>
          <button class="co-btn ok" title="接听" @click="call.acceptCall()">✓</button>
        </template>
        <!-- 呼出/接通/通话中 -->
        <template v-else>
          <button v-if="call.state.phase === 'active' && call.state.media === 'audio'" class="co-btn"
                  :class="{ off: call.state.muted }" title="静音" @click="call.toggleMute()">{{ call.state.muted ? '🔇' : '🎙' }}</button>
          <button v-if="call.state.phase === 'active' && call.state.media === 'video'" class="co-btn"
                  :class="{ off: call.state.camOff }" title="摄像头" @click="call.toggleCam()">{{ call.state.camOff ? '🚫' : '🎥' }}</button>
          <button class="co-btn danger" title="挂断" @click="call.hangUp()">✕</button>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CallManager } from './call'
import { assetUrl, fmtDuration } from './util'

const props = defineProps<{ call: CallManager }>()

const remoteEl = ref<HTMLVideoElement>()
const localEl = ref<HTMLVideoElement>()

const statusText = computed(() => {
  switch (props.call.state.phase) {
    case 'outgoing': return '等待对方接听…'
    case 'incoming': return `邀请你${props.call.state.media === 'video' ? '视频' : '语音'}通话`
    case 'connecting': return '连接中…'
    case 'active': return fmtDuration((Date.now() - props.call.state.startedAt) / 1000)
    default: return ''
  }
})

// 媒体流绑定到 video 元素（state.stream 变化时重新 attach）
watch(() => props.call.state.remoteStream, s => {
  if (remoteEl.value) remoteEl.value.srcObject = s
})
watch(() => props.call.state.localStream, s => {
  if (localEl.value) localEl.value.srcObject = s
})

// 通话计时（active 秒级刷新）
let timer: number | null = null
onMounted(() => { timer = window.setInterval(() => { void props.call.state.startedAt }, 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.co-mask { position: fixed; inset: 0; z-index: 95; background: rgba(10, 12, 16, .82); display: flex; align-items: center; justify-content: center; }
.co {
  width: 320px; padding: 34px 24px; border-radius: 18px; background: rgba(28, 30, 36, .92); color: #fff;
  display: flex; flex-direction: column; align-items: center; gap: 14px; box-shadow: 0 12px 48px rgba(0,0,0,.5);
}
.co.video { width: 640px; max-width: 94vw; height: 70vh; position: relative; justify-content: flex-end; }
.co-remote { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; border-radius: 18px; background: #000; }
.co-local {
  position: absolute; right: 14px; top: 14px; width: 140px; height: 100px; object-fit: cover;
  border-radius: 10px; background: #000; box-shadow: 0 2px 12px rgba(0,0,0,.5); z-index: 1;
}
.co-peer { display: flex; flex-direction: column; align-items: center; gap: 8px; z-index: 2; }
.co-face { width: 72px; height: 72px; border-radius: 16px; object-fit: cover; }
.co-face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 28px; font-weight: 600; }
.co-name { font-size: 17px; }
.co-status { font-size: 12px; opacity: .75; }
.co-error { font-size: 12px; color: #ff8f9d; }
.co-actions { display: flex; gap: 22px; z-index: 2; }
.co-btn {
  width: 54px; height: 54px; border-radius: 50%; border: none; cursor: pointer; font-size: 20px;
  background: rgba(255, 255, 255, .16); color: #fff;
}
.co-btn.ok { background: #34c777; }
.co-btn.danger { background: #fa5151; }
.co-btn.off { opacity: .65; }
</style>
