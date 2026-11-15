<template>
  <div class="mb" @click.stop="$emit('preview')">
    <div class="mb-title">{{ elem?.title || '[聊天记录]' }}</div>
    <div v-for="(a, i) in abstracts" :key="i" class="mb-abstract">{{ a }}</div>
    <div class="mb-foot">查看 {{ count }} 条转发消息</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ elem: any }>()
defineEmits<{ preview: [] }>()

const abstracts = computed(() => (props.elem?.abstract_list || props.elem?.abstractList || []).slice(0, 3))
const count = computed(() => (props.elem?.multiMessage || props.elem?.messages || []).length || 0)
</script>

<style scoped>
.mb { cursor: pointer; min-width: 200px; max-width: 300px; display: flex; flex-direction: column; gap: 4px; }
.mb-title { font-weight: 600; font-size: 13px; }
.mb-abstract {
  font-size: 12px; color: var(--im-text-2); white-space: nowrap; overflow: hidden;
  text-overflow: ellipsis; max-width: 300px;
}
.mb-foot { margin-top: 4px; padding-top: 6px; border-top: 1px solid var(--im-border); font-size: 11px; color: var(--im-text-2); }
</style>
