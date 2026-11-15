<template>
  <span class="tb">
    <template v-for="(seg, i) in segments" :key="i">
      <a v-if="seg.kind === 'link'" class="tb-link" :href="seg.text" target="_blank" rel="noopener noreferrer" @click.stop>{{ seg.text }}</a>
      <span v-else-if="seg.kind === 'at'" class="tb-at">{{ seg.text }}</span>
      <template v-else>{{ seg.text }}</template>
    </template>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ text: string; atUsers?: { atUserID: string; groupNickname: string }[] }>()

/** 纯计算分段：URL / @提及 / 普通文本（避免 v-html 注入面） */
const segments = computed(() => {
  const text = props.text || ''
  type Seg = { kind: 'text' | 'link' | 'at'; text: string }
  const marks: { start: number; end: number; kind: 'link' | 'at'; text: string }[] = []
  // URL：http(s) 直链
  for (const m of text.matchAll(/https?:\/\/[^\s<>@"']+/g)) {
    marks.push({ start: m.index!, end: m.index! + m[0].length, kind: 'link', text: m[0] })
  }
  // @提及：优先按 atUsersInfo 的群昵称精确匹配
  const used = new Set(marks)
  for (const u of props.atUsers || []) {
    if (!u.groupNickname) continue
    const token = '@' + u.groupNickname
    let from = 0
    for (;;) {
      const i = text.indexOf(token, from)
      if (i < 0) break
      from = i + token.length
      if (marks.some(k => used.has(k) && i < k.end && i + token.length > k.start)) continue
      marks.push({ start: i, end: from, kind: 'at', text: token })
      used.add(marks[marks.length - 1])
      break
    }
  }
  marks.sort((a, b) => a.start - b.start)
  const out: Seg[] = []
  let pos = 0
  for (const k of marks) {
    if (k.start < pos) continue
    if (k.start > pos) out.push({ kind: 'text', text: text.slice(pos, k.start) })
    out.push({ kind: k.kind, text: k.text })
    pos = k.end
  }
  if (pos < text.length) out.push({ kind: 'text', text: text.slice(pos) })
  return out.length ? out : [{ kind: 'text', text }]
})
</script>

<style scoped>
.tb { white-space: pre-wrap; word-break: break-word; }
.tb-link { color: var(--im-primary); word-break: break-all; }
.tb-at { color: var(--im-primary); font-weight: 600; }
</style>
