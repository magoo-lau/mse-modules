<template>
  <div v-if="visible" class="atp">
    <div v-for="m in filtered" :key="m.userID" class="atp-item" @mousedown.prevent="$emit('pick', m)">
      <img v-if="m.faceURL" class="face" :src="assetUrl(m.faceURL)" alt="" />
      <span v-else class="face char" :class="{ bot: isBotUserId(m.userID) }">{{ isBotUserId(m.userID) ? '🤖' : (m.nickname || m.userID || '?').charAt(0) }}</span>
      <span class="nm">{{ m.nickname || m.userID }}</span>
      <span v-if="m.userID === meId" class="tag">我</span>
    </div>
    <div class="atp-item" @mousedown.prevent="$emit('pick-all')">
      <span class="face char all">@</span>
      <span class="nm">所有人</span>
    </div>
    <div v-if="!filtered.length" class="atp-empty">无匹配成员</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { assetUrl, isBotUserId } from './util'

const props = defineProps<{ visible: boolean; members: any[]; keyword: string; meId: string }>()
defineEmits<{ pick: [m: any]; 'pick-all': [] }>()

const filtered = computed(() => {
  const k = (props.keyword || '').trim().toLowerCase()
  const list = props.members
  if (!k) return list.slice(0, 20)
  return list.filter(m => (m.nickname || '').toLowerCase().includes(k) || (m.userID || '').includes(k)).slice(0, 20)
})
</script>

<style scoped>
.atp {
  position: absolute; bottom: 100%; left: 0; margin-bottom: 6px; width: 260px; max-height: 220px; overflow: auto;
  background: var(--im-panel-bg); border: 1px solid var(--im-border); border-radius: 10px;
  box-shadow: 0 4px 20px var(--im-shadow); z-index: 30; padding: 4px;
}
.atp-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.atp-item:hover { background: var(--im-hover); }
.atp-item .nm { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.atp-item .tag { font-size: 11px; color: var(--im-text-2); }
.face { width: 26px; height: 26px; border-radius: 6px; flex: none; object-fit: cover; }
.face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 12px; }
.face.char.all { background: var(--im-active); color: var(--im-primary); font-weight: 700; }
.atp-empty { color: var(--im-text-2); font-size: 12px; text-align: center; padding: 12px 0; }
</style>
