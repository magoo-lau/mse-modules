<template>
  <div class="fb" @click.stop="download">
    <span class="fb-icon">{{ iconChar }}</span>
    <span class="fb-main">
      <span class="fb-name">{{ fileName }}</span>
      <span class="fb-size">{{ sizeText }}</span>
    </span>
    <span class="fb-dl" title="下载">↓</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { fileExt, fileIconChar, fmtSize } from '../util'

const props = defineProps<{ elem: any }>()

const fileName = computed(() => props.elem?.fileName || '未命名文件')
const sizeText = computed(() => fmtSize(props.elem?.fileSize))
const iconChar = computed(() => fileIconChar(fileName.value))

async function download() {
  const url = props.elem?.sourceUrl
  if (!url) return
  try {
    // 同源/允许跨域：blob 下载保留原文件名；否则退化为直接打开
    const resp = await fetch(url)
    if (!resp.ok) throw new Error('bad status')
    const blob = await resp.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = fileName.value || fileExt(url) || 'file'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(a.href), 30_000)
  } catch {
    window.open(url, '_blank')
  }
}
</script>

<style scoped>
.fb { display: flex; align-items: center; gap: 10px; cursor: pointer; min-width: 180px; max-width: 300px; }
.fb-icon {
  flex: none; width: 38px; height: 38px; border-radius: 8px; background: var(--im-active);
  color: var(--im-primary); display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700;
}
.fb-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.fb-name { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fb-size { font-size: 11px; color: var(--im-text-2); }
.fb-dl { flex: none; font-size: 16px; color: var(--im-text-2); }
.fb:hover .fb-dl { color: var(--im-primary); }
</style>
