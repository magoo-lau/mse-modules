<template>
  <a class="lc" :href="card.url" target="_blank" rel="noopener noreferrer" @click.stop>
    <img v-if="card.thumbUrl" class="lc-thumb" :src="card.thumbUrl" alt="" @error="thumbFail = true" v-show="!thumbFail" />
    <span class="lc-main">
      <span class="lc-title">{{ card.title || card.url }}</span>
      <span v-if="card.desc" class="lc-desc">{{ card.desc }}</span>
      <span class="lc-url">{{ card.url }}</span>
    </span>
  </a>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { LinkCardData } from '../custom'

const props = defineProps<{ card: LinkCardData }>()
const thumbFail = ref(false)
const card = computed(() => props.card)
</script>

<style scoped>
.lc { display: flex; gap: 10px; max-width: 300px; text-decoration: none; color: inherit; }
.lc-thumb { width: 56px; height: 56px; border-radius: 8px; object-fit: cover; flex: none; background: var(--im-hover); }
.lc-main { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.lc-title { font-weight: 600; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.lc-desc { font-size: 12px; color: var(--im-text-2); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.lc-url { font-size: 11px; color: var(--im-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.lc:hover .lc-title { text-decoration: underline; }
</style>
