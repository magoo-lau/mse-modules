<template>
  <div class="page">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-select v-model="status" placeholder="状态" clearable style="width: 130px" @change="load(1)">
          <el-option label="待执行" :value="0" />
          <el-option label="执行中" :value="1" />
          <el-option label="成功" :value="2" />
          <el-option label="失败" :value="3" />
        </el-select>
        <el-select v-model="queryTaskType" placeholder="任务类型" clearable style="width: 140px" @change="load(1)">
          <el-option label="全量重建" value="FULL_REBUILD" />
          <el-option label="索引优化" value="INDEX_OPTIMIZE" />
          <el-option label="立即扫描" value="SCAN_NOW" />
        </el-select>
        <el-button @click="load()">刷新</el-button>
      </div>

      <div class="create-bar">
        <el-select v-model="formTaskType" placeholder="任务类型" clearable style="width: 140px">
          <el-option label="全量重建" value="FULL_REBUILD" />
          <el-option label="索引优化" value="INDEX_OPTIMIZE" />
          <el-option label="立即扫描" value="SCAN_NOW" />
        </el-select>
        <el-select v-if="formTaskType && formTaskType !== 'INDEX_OPTIMIZE'" v-model="sourceCode" placeholder="目标源" style="width: 220px">
          <el-option v-for="s in sources" :key="s.sourceCode" :label="`${s.sourceName}（${s.sourceCode}）`" :value="s.sourceCode" />
        </el-select>
        <el-button v-permission="'search:task:save'" type="primary" @click="createTask">创建任务</el-button>
        <span class="hint">idleOnly 任务仅在闲时窗口（{{ idleWindowHint }}）内执行，工作时间触发将挂起等待；挂起任务可点行内「立即执行」改约</span>
      </div>

      <el-table :data="rows" size="small" v-loading="loading" border stripe>
        <el-table-column prop="id" label="任务号" width="170" show-overflow-tooltip />
        <el-table-column label="类型" width="110" align="center">
          <template #default="{ row }">{{ typeLabel(row.taskType) }}</template>
        </el-table-column>
        <el-table-column label="目标源" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ sourceLabel(row.sourceCode) }}</template>
        </el-table-column>
        <el-table-column label="执行方式" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.idleOnly === 1 ? 'warning' : 'success'" size="small">
              {{ row.idleOnly === 1 ? '闲时' : '立即' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="150">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" :stroke-width="6" />
          </template>
        </el-table-column>
        <el-table-column prop="processedRows" label="处理条数" width="90" align="center" />
        <el-table-column prop="errorMsg" label="失败原因" min-width="160" show-overflow-tooltip />
        <el-table-column prop="startTime" label="开始时间" width="160" show-overflow-tooltip />
        <el-table-column prop="endTime" label="结束时间" width="160" show-overflow-tooltip />
        <el-table-column label="操作" width="100" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 0 && row.idleOnly === 1"
              v-permission="'search:task:save'"
              type="primary"
              link
              size="small"
              @click="runNow(row)">立即执行</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="pageNum" v-model:size="pageSize" @change="load()" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { Pagination } from 'mse-ui-shared/pc'

const rows = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const status = ref<number | undefined>()
const queryTaskType = ref<string>()
const formTaskType = ref<string>()
const sourceCode = ref<string>()
const sources = ref<any[]>([])
const loading = ref(false)
const idleWindowHint = ref('00:00-07:00')

const typeLabels: Record<string, string> = {
  FULL_REBUILD: '全量重建',
  INDEX_OPTIMIZE: '索引优化',
  SCAN_NOW: '立即扫描'
}

function typeLabel(type: string) {
  return typeLabels[type] || type
}

function statusLabel(status: number) {
  return ['待执行', '执行中', '成功', '失败'][status] ?? String(status)
}

function statusType(status: number) {
  return ['info', 'warning', 'success', 'danger'][status] ?? 'info'
}

async function load(page?: number) {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const data: any = await request.get('/search/task/page', {
      params: { pageNum: pageNum.value, pageSize: pageSize.value, status: status.value, taskType: queryTaskType.value || undefined }
    })
    rows.value = data.records || []
    total.value = Number(data.total || 0)
  } finally {
    loading.value = false
  }
}

async function createTask() {
  if (!formTaskType.value) {
    ElMessage.warning('请先选择任务类型')
    return
  }
  if (formTaskType.value !== 'INDEX_OPTIMIZE' && !sourceCode.value) {
    ElMessage.warning('请选择目标源')
    return
  }
  await request.post('/search/task', {
    taskType: formTaskType.value,
    sourceCode: formTaskType.value === 'INDEX_OPTIMIZE' ? undefined : sourceCode.value,
    idleOnly: formTaskType.value !== 'SCAN_NOW'
  })
  ElMessage.success('任务已创建，等待调度执行')
  load(1)
}

async function runNow(row: any) {
  await ElMessageBox.confirm(
    `确认立即执行该${typeLabel(row.taskType)}任务？将不再等待闲时窗口，调度器下一轮（约 30 秒内）拾起执行`,
    '立即执行',
    { type: 'warning' }
  )
  await request.post(`/search/task/${row.id}/run-now`)
  ElMessage.success('已改为立即执行，等待调度')
  load()
}

async function loadSources() {
  const data: any = await request.get('/search/source/page', { params: { pageNum: 1, pageSize: 100 } })
  sources.value = data.records || []
}

const sourceNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of sources.value) map[s.sourceCode] = s.sourceName
  return map
})

function sourceLabel(code?: string) {
  if (!code) return ''
  const name = sourceNameMap.value[code]
  return name ? `${name}（${code}）` : code
}

onMounted(() => {
  load(1)
  loadSources()
})
</script>

<style scoped>
.page {
  padding: 12px;
}
.filter-bar,
.create-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
  align-items: center;
}
.create-bar {
  padding: 8px 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.hint {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
