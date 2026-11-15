<template>
  <div v-if="visible" class="sp-mask" @click.self="$emit('close')">
    <div class="sp">
      <div class="sp-head">
        <b>消息搜索</b>
        <button class="x" @click="$emit('close')">×</button>
      </div>
      <div class="sp-search">
        <input v-model="keyword" placeholder="输入关键词，搜索全部会话" @keyup.enter="doSearch" />
        <button class="primary sm" :disabled="!keyword.trim() || searching" @click="doSearch">{{ searching ? '搜索中…' : '搜索' }}</button>
      </div>
      <div class="sp-body">
        <template v-for="g in groups" :key="g.conversationID">
          <div class="sp-conv">{{ g.showName || g.conversationID }}（{{ g.messageCount }}）</div>
          <div v-for="m in g.messageList" :key="m.clientMsgID" class="sp-msg" @click="$emit('open', { conversationID: g.conversationID, msg: m })">
            <span class="sp-who">{{ m.senderNickname || m.sendID }}</span>
            <span class="sp-time">{{ fmtTime(m.sendTime) }}</span>
            <span class="sp-text">{{ summaryOf(m) }}</span>
          </div>
        </template>
        <div v-if="searched && !groups.length" class="sp-empty">无匹配结果</div>
        <div v-if="!searched" class="sp-empty sp-tip">支持按关键词搜索本地已同步的消息</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { WasmSdk } from '@openim/wasm-client-sdk'
import { fmtTime } from './util'
import { summaryOf } from './summary'

const props = defineProps<{ visible: boolean; sdk: WasmSdk | null }>()
defineEmits<{ close: []; open: [p: { conversationID: string; msg: any }] }>()

const keyword = ref('')
const searching = ref(false)
const searched = ref(false)
const groups = ref<any[]>([])

watch(() => props.visible, v => {
  if (v) { keyword.value = ''; groups.value = []; searched.value = false }
})

async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw || !props.sdk) return
  searching.value = true
  try {
    const res: any = await props.sdk.searchLocalMessages({ conversationID: '', keywordList: [kw], count: 100 } as any)
    const d = res?.data ?? res
    groups.value = d?.searchResultItems ?? d?.findResultItems ?? []
  } catch {
    groups.value = []
  } finally {
    searching.value = false
    searched.value = true
  }
}
</script>

<style scoped>
.sp-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .35); z-index: 60; display: flex; align-items: center; justify-content: center; }
.sp { width: 560px; max-width: 94vw; height: 70vh; background: var(--im-panel-bg); border-radius: 12px; box-shadow: 0 8px 32px var(--im-shadow); display: flex; flex-direction: column; overflow: hidden; }
.sp-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid var(--im-border); }
.sp-head .x { border: none; background: transparent; font-size: 20px; color: var(--im-text-2); cursor: pointer; }
.sp-search { display: flex; gap: 8px; padding: 10px 16px; }
.sp-search input { flex: 1; padding: 7px 10px; border: 1px solid var(--im-border); border-radius: 8px; background: var(--im-input-bg); color: var(--im-text); outline: none; }
.sm { padding: 6px 14px; font-size: 12px; }
.sp-body { flex: 1; overflow: auto; padding: 4px 16px 14px; }
.sp-conv { margin: 10px 0 4px; font-size: 12px; font-weight: 600; color: var(--im-text-2); }
.sp-msg { display: flex; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; align-items: baseline; }
.sp-msg:hover { background: var(--im-hover); }
.sp-who { flex: none; font-weight: 600; max-width: 100px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sp-time { flex: none; font-size: 11px; color: var(--im-text-2); }
.sp-text { flex: 1; min-width: 0; color: var(--im-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sp-empty { color: var(--im-text-2); font-size: 12px; text-align: center; padding: 30px 0; }
.sp-tip { line-height: 1.8; }
</style>
