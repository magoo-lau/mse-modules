<template>
  <div class="loc">
    <span class="loc-pin">📍</span>
    <span class="loc-main">
      <span class="loc-desc">{{ elem?.description || '位置' }}</span>
      <span class="loc-coord">{{ lat }}, {{ lng }}</span>
    </span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ elem: any }>()
const lat = computed(() => Number(props.elem?.latitude || 0).toFixed(5))
const lng = computed(() => Number(props.elem?.longitude || 0).toFixed(5))
</script>

<style scoped>
.loc { display: flex; gap: 8px; align-items: flex-start; max-width: 280px; }
.loc-pin { flex: none; }
.loc-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.loc-desc { font-size: 13px; }
.loc-coord { font-size: 11px; color: var(--im-text-2); }
</style>
