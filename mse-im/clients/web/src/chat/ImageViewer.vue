<template>
  <div v-if="visible" class="iv-mask" @click.self="$emit('close')">
    <div class="iv-bar">
      <a class="iv-dl" :href="url" :download="''" target="_blank" rel="noopener" @click.stop>下载</a>
      <button class="iv-x" @click="$emit('close')">×</button>
    </div>
    <video v-if="mode === 'video'" class="iv-video" :src="url" controls autoplay></video>
    <img v-else class="iv-img" :src="url" alt="" />
  </div>
</template>

<script setup lang="ts">
defineProps<{ visible: boolean; url: string; mode: 'image' | 'video' }>()
defineEmits<{ close: [] }>()
</script>

<style scoped>
.iv-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .78); z-index: 90; display: flex; align-items: center; justify-content: center; }
.iv-bar { position: fixed; top: 14px; right: 18px; display: flex; gap: 10px; z-index: 1; }
.iv-dl { color: #fff; font-size: 13px; text-decoration: none; opacity: .85; }
.iv-dl:hover { opacity: 1; }
.iv-x { border: none; background: transparent; color: #fff; font-size: 26px; cursor: pointer; line-height: 1; }
.iv-img, .iv-video { max-width: 92vw; max-height: 88vh; object-fit: contain; }
</style>
