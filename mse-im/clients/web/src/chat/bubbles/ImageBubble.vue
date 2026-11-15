<template>
  <img class="ib" :src="url" alt="图片" loading="lazy"
       :style="{ maxWidth: '260px', maxHeight: '260px', width: autoW, height: autoH }"
       @click.stop="url && $emit('view', url)" @error="onError" />
  <span v-if="failed" class="ib-broken">🖼 图片加载失败</span>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{ elem: any }>()
defineEmits<{ view: [url: string] }>()

const failed = ref(false)
/** 候选源：优先缩略图（列表加载快），加载失败逐级回退大图/原图 */
const candidates = computed(() =>
  [props.elem?.snapshotPicture?.url, props.elem?.bigPicture?.url, props.elem?.sourcePicture?.url]
    .filter((u): u is string => !!u))
const candIdx = ref(0)
const url = computed(() => candidates.value[candIdx.value] || '')
const w = computed(() => props.elem?.snapshotPicture?.width || props.elem?.bigPicture?.width || 0)
const h = computed(() => props.elem?.snapshotPicture?.height || props.elem?.bigPicture?.height || 0)
/** 按已知宽高占位防抖动（260px 上限等比缩放） */
const autoW = computed(() => (w.value && h.value ? Math.round(Math.min(1, 260 / Math.max(w.value, h.value)) * w.value) + 'px' : undefined))
const autoH = computed(() => (w.value && h.value ? Math.round(Math.min(1, 260 / Math.max(w.value, h.value)) * h.value) + 'px' : undefined))

/** URL 变化（发送回执补全 / 回退切换）即复位失败态，避免空 src 期间的 error 残留 */
watch(url, () => { failed.value = false })

function onError() {
  if (candIdx.value < candidates.value.length - 1) {
    candIdx.value++
  } else {
    failed.value = true
  }
}
</script>

<style scoped>
.ib { border-radius: 8px; display: block; cursor: zoom-in; object-fit: cover; background: var(--im-hover); }
.ib-broken { font-size: 12px; color: var(--im-text-2); }
</style>
