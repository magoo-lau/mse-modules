<template>
  <div class="vb" :style="{ width: boxW + 'px' }" @click.stop="$emit('play', videoUrl)">
    <img v-if="snapUrl" class="vb-snap" :src="snapUrl" alt="" />
    <div v-else class="vb-snap vb-holder">视频</div>
    <span class="vb-play">▶</span>
    <span v-if="duration" class="vb-dur">{{ duration }}"</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ elem: any }>()
defineEmits<{ play: [url: string] }>()

const snapUrl = computed(() => props.elem?.snapshotUrl || '')
const videoUrl = computed(() => props.elem?.videoUrl || '')
const duration = computed(() => Math.round(props.elem?.duration || 0))
const w = computed(() => props.elem?.snapshotWidth || 260)
const h = computed(() => props.elem?.snapshotHeight || 0)
const boxW = computed(() => (w.value && h.value ? Math.max(120, Math.min(260, Math.round(200 * (w.value / Math.max(w.value, h.value))))) : 220))
</script>

<style scoped>
.vb { position: relative; border-radius: 8px; overflow: hidden; cursor: zoom-in; background: #000; }
.vb-snap { display: block; width: 100%; max-height: 220px; object-fit: cover; }
.vb-holder { display: flex; align-items: center; justify-content: center; height: 120px; color: #fff; font-size: 13px; opacity: .7; }
.vb-play {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 40px; height: 40px; border-radius: 50%; background: rgba(0, 0, 0, .55); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 15px; pointer-events: none;
}
.vb-dur {
  position: absolute; right: 6px; bottom: 6px; padding: 1px 6px; border-radius: 6px;
  background: rgba(0, 0, 0, .55); color: #fff; font-size: 11px;
}
</style>
