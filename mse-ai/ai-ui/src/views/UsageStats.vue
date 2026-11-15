<template>
  <div class="page">
    <el-table :data="summary" border size="small" style="margin-bottom: 16px">
      <el-table-column prop="day" label="日期" width="120" />
      <el-table-column prop="calls" label="调用次数" width="100" />
      <el-table-column prop="failedCalls" label="失败" width="80" />
      <el-table-column prop="promptTokens" label="输入 tokens" width="120" />
      <el-table-column prop="completionTokens" label="输出 tokens" width="120" />
      <el-table-column prop="totalTokens" label="合计 tokens" width="120" />
    </el-table>
    <div class="toolbar">
      <el-select v-model="agentCode" placeholder="全部智能体" clearable style="width: 200px" @change="load">
        <el-option v-for="a in agents" :key="a.agentCode" :label="a.name" :value="a.agentCode" />
      </el-select>
      <el-select v-model="channel" placeholder="全部通道" clearable style="width: 140px" @change="load">
        <el-option label="网关" value="gateway" />
        <el-option label="RPC" value="rpc" />
        <el-option label="开放 API" value="openapi" />
      </el-select>
    </div>
    <el-table :data="rows" border size="small">
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column label="通道" width="90">
        <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
      </el-table-column>
      <el-table-column label="智能体" width="140">
        <template #default="{ row }">{{ agentLabel(row.agentCode) }}</template>
      </el-table-column>
      <el-table-column prop="model" label="模型" width="160" />
      <el-table-column prop="totalTokens" label="tokens" width="90" />
      <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
      <el-table-column label="结果" width="70">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'danger'" size="small">
            {{ row.status === 0 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="errorMsg" label="失败原因" min-width="140" show-overflow-tooltip />
    </el-table>
    <Pagination :total="total" v-model:page="pageNum" v-model:size="pageSize" @change="load" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { agentApi, usageApi } from '../api/ai'
import type { AiAgent } from '../api/ai'
import { Pagination } from 'mse-ui-shared/pc'

const summary = ref<any[]>([])
const rows = ref<any[]>([])
const agents = ref<AiAgent[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const agentCode = ref('')
const channel = ref('')

const CHANNEL_LABELS: Record<string, string> = { gateway: '网关', rpc: 'RPC', openapi: '开放 API' }

function channelLabel(channel?: string) {
  return channel ? CHANNEL_LABELS[channel] || channel : '—'
}

/** agentCode 映射名称（未知编码原样保留） */
function agentLabel(agentCode?: string) {
  if (!agentCode) return '—'
  return agents.value.find((a) => a.agentCode === agentCode)?.name || agentCode
}

onMounted(async () => {
  await load()
  agents.value = (await agentApi.visible().catch(() => [])) as any
})

async function load() {
  const sum: any = await usageApi.summary()
  summary.value = (sum ?? []).slice(0, 14)
  const body: any = await usageApi.page({
    pageNum: pageNum.value,
    pageSize: pageSize.value,
    agentCode: agentCode.value || undefined,
    channel: channel.value || undefined
  })
  rows.value = body?.records ?? []
  total.value = Number(body?.total ?? 0)
}
</script>

<style scoped>
.page {
  padding: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
