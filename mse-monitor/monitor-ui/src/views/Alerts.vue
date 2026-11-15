<template>
  <div class="page">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="load">
          <el-option label="触发中" value="firing" />
          <el-option label="已恢复" value="resolved" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="目标 / 内容关键字" clearable style="width: 220px" @keyup.enter="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button
          v-permission="'monitor:notify:query'"
          type="primary"
          plain
          @click="notifyVisible = true"
        >通知设置</el-button>
      </div>

      <el-table :data="rows" size="small" v-loading="loading" row-key="id">
        <el-table-column prop="targetName" label="目标" min-width="120" show-overflow-tooltip />
        <el-table-column label="级别" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level === 'error' ? 'danger' : 'warning'">{{ row.level === 'error' ? '严重' : '警告' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="规则" width="100">
          <template #default="{ row }">{{ RULE_LABELS[row.ruleKey] || row.ruleKey }}</template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="240" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'firing' ? 'danger' : 'success'">{{ row.status === 'firing' ? '触发中' : '已恢复' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="首次触发" width="160">
          <template #default="{ row }">{{ fmt(row.firstTriggerTime) }}</template>
        </el-table-column>
        <el-table-column label="最近触发" width="160">
          <template #default="{ row }">{{ fmt(row.lastTriggerTime) }}</template>
        </el-table-column>
        <el-table-column label="恢复时间" width="160">
          <template #default="{ row }">{{ fmt(row.resolveTime) }}</template>
        </el-table-column>
        <el-table-column label="通知" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ notifyText(row.notifyResult) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'firing'"
              v-permission="'monitor:alert:handle'"
              text
              type="primary"
              size="small"
              @click="handle(row)"
            >处理</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
    </el-card>

    <NotifySettingDialog v-model="notifyVisible" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { handleAlert, pageAlerts, type MonAlert } from '../api/monitor'
import NotifySettingDialog from '../components/NotifySettingDialog.vue'
import { Pagination } from 'mse-ui-shared/pc'

const RULE_LABELS: Record<string, string> = {
  down: '探测失败', latency: '响应超时', cpu: 'CPU 过高', mem: '内存过高',
  disk: '磁盘过高', redis_mem: 'Redis 内存', mysql_threads: '连接数过高'
}

const query = reactive({ pageNum: 1, pageSize: 20, status: '', keyword: '' })
const rows = ref<MonAlert[]>([])
const total = ref(0)
const loading = ref(false)
const notifyVisible = ref(false)

const fmt = (time?: string) => (time ? time.replace('T', ' ').slice(0, 19) : '-')

function notifyText(result?: string): string {
  if (!result) return '-'
  try {
    const map = JSON.parse(result) as Record<string, string>
    return Object.entries(map)
      .map(([channel, state]) => `${channel === 'inbox' ? '站内信' : 'Webhook'}:${state === 'ok' ? '已发送' : state}`)
      .join('；')
  } catch {
    return '-'
  }
}

async function load() {
  loading.value = true
  try {
    const page = await pageAlerts({ ...query })
    rows.value = page.records
    total.value = Number(page.total ?? 0)
  } finally {
    loading.value = false
  }
}

async function handle(row: MonAlert) {
  await ElMessageBox.confirm(`确认手动处理「${row.targetName}」的该条告警？将转为已恢复并发送恢复通知。`, '处理确认', { type: 'warning' })
  await handleAlert(row.id)
  ElMessage.success('已处理')
  load()
}

onMounted(load)
</script>

<style scoped>
.page {
  padding: 12px;
}
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
</style>
