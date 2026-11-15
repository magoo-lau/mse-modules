<template>
  <div class="page">
    <div class="toolbar">
      <el-select v-if="auth.platform" v-model="tenantId" filterable clearable placeholder="全部租户"
                 style="width: 220px" @change="load">
        <el-option v-for="t in tenantOptions" :key="t.id"
                   :label="t.status === 0 ? `${t.name}（${t.code}）` : `${t.name}（${t.code}，已停用）`"
                   :value="String(t.id)" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-table :data="summary" border size="small" style="margin-bottom: 16px">
      <el-table-column prop="day" label="日期" width="120" />
      <el-table-column prop="calls" label="调用次数" width="100" />
      <el-table-column prop="failedCalls" label="失败" width="80" />
      <el-table-column prop="promptTokens" label="输入 tokens" width="120" />
      <el-table-column prop="completionTokens" label="输出 tokens" width="120" />
      <el-table-column prop="totalTokens" label="合计 tokens" width="120" />
    </el-table>
    <el-table :data="rows" border size="small">
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column v-if="auth.platform" label="租户" width="140">
        <template #default="{ row }">{{ tenantName(row.tenantId) }}</template>
      </el-table-column>
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
    </el-table>
    <Pagination :total="total" v-model:page="pageNum" v-model:size="pageSize" @change="load" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { agentApi, platformApi, usageApi } from '../api/ai'
import type { AiAgent, TenantOption } from '../api/ai'
import { useAuthStore } from '../stores/auth'
import { Pagination } from 'mse-ui-shared/pc'

const auth = useAuthStore()
/** 租户态查询域固定当前租户（后端同样按会话锁定）；平台态可筛选 */
const currentTenantId = () => (auth.platform ? undefined : Number(auth.tenantId) || undefined)

const summary = ref<any[]>([])
const rows = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const tenantId = ref('')

/** 租户/智能体下拉数据（加载失败置空仅影响选项与名称回显，不阻塞页面） */
const tenantOptions = ref<TenantOption[]>([])
const agents = ref<AiAgent[]>([])

const CHANNEL_LABELS: Record<string, string> = { gateway: '网关', rpc: 'RPC', openapi: '开放 API' }

function channelLabel(channel?: string) {
  return channel ? CHANNEL_LABELS[channel] || channel : '—'
}

/** 租户 ID 映射名称（选项缺失时回退显示 ID） */
function tenantName(tenantId: any) {
  const t = tenantOptions.value.find((o) => String(o.id) === String(tenantId))
  return t ? t.name : tenantId
}

/** agentCode 映射名称（未知编码原样保留） */
function agentLabel(agentCode?: string) {
  if (!agentCode) return '—'
  return agents.value.find((a) => a.agentCode === agentCode)?.name || agentCode
}

onMounted(() => {
  load()
  platformApi.tenantOptions().then((opts: any) => (tenantOptions.value = opts ?? [])).catch(() => {})
  agentApi.options()
    .then((list: any) => (agents.value = list ?? []))
    .catch(() => {})
})

async function load() {
  const tid = auth.platform ? (tenantId.value ? Number(tenantId.value) : undefined) : currentTenantId()
  const sum: any = await usageApi.platformSummary({ days: 14, tenantId: tid })
  summary.value = sum ?? []
  const body: any = await usageApi.platformPage({ pageNum: pageNum.value, pageSize: pageSize.value, tenantId: tid })
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
