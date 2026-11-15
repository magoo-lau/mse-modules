<template>
  <div class="voice" :style="{ width: barWidth + 'px' }" @click.stop="toggle">
    <span class="voice-icon" :class="{ playing }">{{ playing ? '❚❚' : '▶' }}</span>
    <span class="voice-waves" aria-hidden="true"><i v-for="n in bars" :key="n" :class="{ hit: playing }" :style="{ animationDelay: (n % 5) * 0.12 + 's' }"></i></span>
    <span class="voice-dur">{{ duration }}"</span>
    <span v-if="!playing && progress > 0 && progress < 100" class="voice-prog">{{ Math.round(progress) }}%</span>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'

const props = defineProps<{ elem: any }>()

const playing = ref(false)
const progress = ref(0)
let audio: HTMLAudioElement | null = null

const duration = computed(() => Math.max(1, Math.round(props.elem?.duration || 1)))
const url = computed(() => props.elem?.sourceUrl || '')
/** 气泡宽度随时长渐增（60s 封顶） */
const barWidth = computed(() => Math.min(200, 64 + duration.value * 2.2))
const bars = computed(() => Math.min(12, Math.max(4, Math.round(duration.value / 3) + 4)))

function stop() {
  audio?.pause()
  if (audio) audio.currentTime = 0
  playing.value = false
  progress.value = 0
}

function toggle() {
  if (!url.value) return
  if (playing.value) { stop(); return }
  if (!audio) {
    audio = new Audio()
    audio.onended = stop
    audio.ontimeupdate = () => { if (audio && audio.duration) progress.value = (audio.currentTime / audio.duration) * 100 }
  }
  audio.src = url.value
  audio.play().then(() => { playing.value = true }).catch(() => { playing.value = false })
}

onBeforeUnmount(stop)
</script>

<style scoped>
.voice { display: flex; align-items: center; gap: 8px; cursor: pointer; user-select: none; min-height: 28px; }
.voice-icon { flex: none; font-size: 12px; width: 14px; text-align: center; }
.voice-waves { display: flex; align-items: center; gap: 2px; flex: 1; height: 16px; }
.voice-waves i { width: 2px; border-radius: 1px; background: currentColor; opacity: .45; height: 40%; }
.voice-waves i:nth-child(2n) { height: 75%; }
.voice-waves i:nth-child(3n) { height: 100%; }
.voice-waves i.hit { animation: vwave .8s ease-in-out infinite; opacity: 1; }
@keyframes vwave { 0%, 100% { transform: scaleY(.5); } 50% { transform: scaleY(1); } }
.voice-dur { flex: none; font-size: 12px; color: var(--im-text-2); }
.voice-prog { flex: none; font-size: 11px; color: var(--im-text-2); }
</style>
