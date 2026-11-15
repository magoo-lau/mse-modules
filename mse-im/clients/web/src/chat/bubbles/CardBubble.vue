<template>
  <div class="card" @click.stop="$emit('open')">
    <img v-if="elem?.faceURL" class="card-face" :src="assetUrl(elem.faceURL)" alt="" />
    <span v-else class="card-face char">{{ (elem?.nickname || elem?.userID || '?').charAt(0) }}</span>
    <span class="card-main">
      <span class="card-name">{{ elem?.nickname || elem?.userID }}</span>
      <span class="card-sub">个人名片</span>
    </span>
  </div>
</template>

<script setup lang="ts">
import { assetUrl } from '../util'

defineProps<{ elem: any }>()
defineEmits<{ open: [] }>()
</script>

<style scoped>
.card { display: flex; align-items: center; gap: 10px; cursor: pointer; min-width: 180px; max-width: 240px; }
.card-face { width: 40px; height: 40px; border-radius: 8px; object-fit: cover; flex: none; }
.card-face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; }
.card-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.card-name { font-weight: 600; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.card-sub { font-size: 11px; color: var(--im-text-2); }
.card:hover { filter: brightness(.98); }
</style>
