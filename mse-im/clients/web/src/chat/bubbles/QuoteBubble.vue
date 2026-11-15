<template>
  <div class="qb">
    <div class="qb-quote" @click.stop="$emit('locate')">
      <span class="qb-ref-name">{{ quotedName }}</span>
      <span class="qb-ref-text">{{ quotedText }}</span>
    </div>
    <TextBubble :text="elem?.text || ''" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import TextBubble from './TextBubble.vue'
import { summaryOf } from '../summary'

const props = defineProps<{ elem: any }>()
defineEmits<{ locate: [] }>()

const quoted = computed(() => props.elem?.quoteMessage)
const quotedName = computed(() => quoted.value?.senderNickname || quoted.value?.sendID || '')
const quotedText = computed(() => summaryOf(quoted.value) || '[消息]')
</script>

<style scoped>
.qb { display: flex; flex-direction: column; gap: 6px; }
.qb-quote {
  padding: 6px 10px; border-left: 3px solid var(--im-primary); background: var(--im-hover);
  border-radius: 6px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; max-width: 320px;
}
.qb-ref-name { font-size: 11px; color: var(--im-primary); font-weight: 600; }
.qb-ref-text {
  font-size: 12px; color: var(--im-text-2); white-space: nowrap; overflow: hidden;
  text-overflow: ellipsis; max-width: 300px;
}
.qb-quote:hover { filter: brightness(.97); }
</style>
