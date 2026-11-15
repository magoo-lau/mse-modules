<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="任务名称" clearable style="width: 160px" @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button v-permission="'data:sync:save'" type="primary" plain @click="openCreate">新建任务</el-button>
    </div>

    <el-table :data="rows" size="small" v-loading="loading">
      <el-table-column prop="name" label="任务" min-width="130" show-overflow-tooltip />
      <el-table-column label="来源" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.sourceType === 'table' ? row.sourceTable : '(自定义 SQL)' }}
        </template>
      </el-table-column>
      <el-table-column prop="targetTable" label="目标 ODS 表" min-width="140" show-overflow-tooltip />
      <el-table-column label="模式" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.syncMode === 'incr' ? 'warning' : 'info'">
            {{ row.syncMode === 'incr' ? '增量' : '全量' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="cron" label="调度" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.cron || '手动' }}</template>
      </el-table-column>
      <el-table-column label="前置任务" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ depName(row.depJobId) }}</template>
      </el-table-column>
      <el-table-column prop="rowCount" label="最近行数" width="90" align="right" />
      <el-table-column prop="lastRunTime" label="最近执行" width="150">
        <template #default="{ row }">{{ fmt(row.lastRunTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="70">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
            {{ row.status === 0 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'data:sync:run'" link type="success" size="small" @click="run(row)">执行</el-button>
          <el-button v-permission="'data:sync:list'" link type="primary" size="small" @click="showLogs(row)">日志
          </el-button>
          <el-button v-permission="'data:sync:save'" link type="primary" size="small" @click="openEdit(row)">编辑
          </el-button>
          <el-button v-permission="'data:sync:save'" link size="small" @click="toggle(row)">
            {{ row.status === 0 ? '停用' : '启用' }}
          </el-button>
          <el-button v-permission="'data:sync:delete'" link type="danger" size="small" @click="remove(row)">删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
  </el-card>

  <el-dialog v-model="dialog" :title="form.id ? '编辑任务' : '新建任务'" width="640px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="任务名称" required>
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="来源数据源" required>
        <el-select v-model="form.datasourceId" filterable style="width: 100%" @change="onDatasourceChange">
          <el-option v-for="ds in datasourceOptions" :key="ds.id" :value="ds.id" :label="dsLabel(ds)" />
        </el-select>
        <div class="form-tip">
          {{ form.datasourceId === 0
            ? '内置中台库：自动按 tenant_id 过滤，仅同步本租户数据'
            : '外部数据源仅限租户自有库，平台主库无法登记为外部数据源' }}
        </div>
      </el-form-item>
      <el-form-item label="来源类型" required>
        <el-radio-group v-model="form.sourceType">
          <el-radio value="table">整表</el-radio>
          <el-radio value="sql">自定义查询</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.sourceType === 'table'" label="源表名" required>
        <el-select v-model="form.sourceTable" filterable allow-create
          placeholder="如 sys_config（未采集的表可直接手输）" style="width: 100%" @change="loadIncrColumns">
          <el-option v-for="t in sourceTableOptions" :key="t" :value="t" :label="t" />
        </el-select>
      </el-form-item>
      <el-form-item v-else label="查询 SQL" required>
        <el-input v-model="form.querySql" type="textarea" :rows="3"
          placeholder="仅 SELECT，不支持参数占位；引用含租户列的表时结果需包含 tenant_id" />
      </el-form-item>
      <el-form-item label="目标 ODS 表" required>
        <el-select v-model="form.targetTable" filterable allow-create
          placeholder="ods_ 开头小写，如 ods_sys_config；不存在自动建表" style="width: 100%">
          <el-option v-for="t in odsTableOptions" :key="t" :value="t" :label="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="同步模式" required>
        <el-radio-group v-model="form.syncMode">
          <el-radio value="full">全量（清空重灌）</el-radio>
          <el-radio value="incr">增量（水位）</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.syncMode === 'incr'" label="水位列" required>
        <el-select v-model="form.incrColumn" filterable allow-create
          placeholder="时间戳或自增 ID 列，如 update_time / id" style="width: 100%">
          <el-option v-for="c in incrColumnOptions" :key="c" :value="c" :label="c" />
        </el-select>
      </el-form-item>
      <el-form-item label="调度 cron">
        <cron-input v-model="form.cron" placeholder="Spring cron 表达式，留空 = 仅手动执行" preview-url="/data/sync/cron-preview" />
      </el-form-item>
      <el-form-item label="前置任务">
        <el-select v-model="form.depJobId" clearable placeholder="前置任务成功后自动级联执行本任务" style="width: 100%">
          <el-option :value="0" label="无" />
          <el-option v-for="j in otherJobs" :key="j.id" :value="j.id" :label="j.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="logDialog" :title="`运行日志：${logJobName}`" width="760px">
    <el-table :data="logs" size="small" v-loading="logLoading">
      <el-table-column label="触发" width="70">
        <template #default="{ row }">{{ row.triggerType === 'manual' ? '手动' : '调度' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 'success' ? 'success' : row.status === 'running' ? 'warning' : 'danger'"
            size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rowCount" label="行数" width="90" align="right" />
      <el-table-column prop="watermarkUsed" label="前水位" width="150" show-overflow-tooltip />
      <el-table-column prop="watermarkNew" label="后水位" width="150" show-overflow-tooltip />
      <el-table-column prop="costMs" label="耗时(ms)" width="90" align="right" />
      <el-table-column prop="startTime" label="开始" width="150">
        <template #default="{ row }">{{ fmt(row.startTime) }}</template>
      </el-table-column>
      <el-table-column prop="errorMsg" label="错误" min-width="160" show-overflow-tooltip />
    </el-table>
    <Pagination :total="logTotal" v-model:page="logQuery.pageNum" v-model:size="logQuery.pageSize" @change="loadLogs" />
    <template #footer>
      <el-button @click="logDialog = false; stopPoll()">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { CronInput } from 'mse-ui-shared/pc'
import { Pagination } from 'mse-ui-shared/pc'

const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '' })
const datasources = ref<any[]>([])
/** 内置中台库来源（datasourceId=0）：自动按租户过滤，共享库业务表取数的合法通道 */
const BUILTIN_SOURCE = { id: 0, name: '中台库（内置 · 自动租户隔离）' }
const datasourceOptions = computed(() => [BUILTIN_SOURCE, ...datasources.value])
const dsLabel = (ds: any) => (ds.id === 0 ? ds.name : `${ds.name}（${ds.host}:${ds.port}/${ds.dbName}）`)
const emptyForm = {
  id: 0, name: '', datasourceId: undefined as number | undefined, sourceType: 'table',
  sourceTable: '', querySql: '', targetTable: '', syncMode: 'full', incrColumn: '', cron: '',
  depJobId: 0 as number, remark: ''
}
const form = reactive<any>({ ...emptyForm })

const logDialog = ref(false)
const logLoading = ref(false)
const logJobName = ref('')
const logJobId = ref<number | null>(null)
const logs = ref<any[]>([])
const logTotal = ref(0)
const logQuery = reactive({ pageNum: 1, pageSize: 10 })
const otherJobs = computed(() => rows.value.filter((r) => r.id !== form.id))
const depName = (id?: number) => (id && id !== 0 ? (rows.value.find((r) => r.id === id)?.name ?? '#' + id) : '-')
let pollTimer: number | undefined

function fmt(value: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

const STATUS_LABELS: Record<string, string> = { running: '执行中', success: '成功', failed: '失败' }
const statusLabel = (s: string) => STATUS_LABELS[s] || s

async function load() {
  loading.value = true
  try {
    const data: any = await request.get('/data/sync/page', { params: query })
    rows.value = data.records || []
    total.value = Number(data.total)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function loadDatasources() {
  try {
    const data: any = await request.get('/data/source/page', { params: { pageNum: 1, pageSize: 100 } })
    datasources.value = data.records || []
  } catch {
    /* 拦截器已提示 */
  }
}

// ---------------- 元数据联动：源表 / 目标 ODS 表 / 水位列选项 ----------------
// 选项加载失败（如无 data:meta:list 权限）时静默置空：均为 allow-create，仍可手输
const sourceTableOptions = ref<string[]>([])
const odsTableOptions = ref<string[]>([])
const incrColumnOptions = ref<string[]>([])
/** 水位列选项缓存，key = `${datasourceId}|${tableName}`，避免重复请求 */
const columnCache = new Map<string, string[]>()

async function loadSourceTables(datasourceId?: number) {
  if (datasourceId == null) {
    sourceTableOptions.value = []
    return
  }
  try {
    const data: any = await request.get('/data/meta/table/page', {
      params: { pageNum: 1, pageSize: 200, category: 'source', datasourceId }
    })
    const names = (data.records || []).map((r: any) => r.tableName).filter(Boolean)
    // 竞态保护：数据源已切换时丢弃过期结果
    if (form.datasourceId === datasourceId) sourceTableOptions.value = names
  } catch {
    sourceTableOptions.value = []
  }
}

async function loadOdsTables() {
  try {
    const data: any = await request.get('/data/meta/table/page', {
      params: { pageNum: 1, pageSize: 200, category: 'ods' }
    })
    odsTableOptions.value = (data.records || []).map((r: any) => r.tableName).filter(Boolean)
  } catch {
    odsTableOptions.value = []
  }
}

function onDatasourceChange() {
  form.sourceTable = ''
  form.incrColumn = ''
  incrColumnOptions.value = []
  loadSourceTables(form.datasourceId)
}

/** 源表变化后取水位列候选：先按表名查元数据 id，再取列清单（缓存按表名） */
async function loadIncrColumns(tableName: string) {
  incrColumnOptions.value = []
  if (!tableName || form.datasourceId == null) return
  const key = `${form.datasourceId}|${tableName}`
  const cached = columnCache.get(key)
  if (cached) {
    incrColumnOptions.value = cached
    return
  }
  try {
    const page: any = await request.get('/data/meta/table/page', {
      params: { pageNum: 1, pageSize: 50, category: 'source', datasourceId: form.datasourceId, keyword: tableName }
    })
    const hit = (page.records || []).find((r: any) => r.tableName === tableName)
    if (!hit) return
    const detail: any = await request.get(`/data/meta/table/${hit.id}`)
    const columns = (detail.columns || []).map((c: any) => c.columnName).filter(Boolean)
    columnCache.set(key, columns)
    // 竞态保护：表名已变时丢弃过期结果
    if (form.sourceTable === tableName) incrColumnOptions.value = columns
  } catch {
    /* 无元数据或无权限：静默置空，仍可手输 */
  }
}

function openCreate() {
  Object.assign(form, emptyForm, { id: 0 })
  sourceTableOptions.value = []
  incrColumnOptions.value = []
  dialog.value = true
}

function openEdit(row: any) {
  Object.assign(form, emptyForm, row, { id: row.id, depJobId: row.depJobId ?? 0 })
  incrColumnOptions.value = []
  // 编辑回显不走 @change，手动补拉表选项与水位列候选（失败静默置空）
  if (row.datasourceId != null) {
    loadSourceTables(row.datasourceId)
    if (row.sourceTable) loadIncrColumns(row.sourceTable)
  }
  dialog.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await request.put('/data/sync', form)
    } else {
      await request.post('/data/sync', form)
    }
    ElMessage.success('已保存')
    dialog.value = false
    load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function run(row: any) {
  await ElMessageBox.confirm(`立即执行任务「${row.name}」？`, '执行确认')
  try {
    await request.post(`/data/sync/${row.id}/run`)
    ElMessage.success('已触发执行，请稍后查看日志')
    showLogs(row)
  } catch {
    /* 拦截器已提示 */
  }
}

async function toggle(row: any) {
  await request.post(`/data/sync/${row.id}/status`, null, { params: { enable: row.status !== 0 } })
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除任务「${row.name}」？目标 ODS 表数据将保留。`, '删除确认', { type: 'warning' })
  await request.delete(`/data/sync/${row.id}`)
  ElMessage.success('已删除')
  load()
}

function showLogs(row: any) {
  logJobName.value = row.name
  logJobId.value = row.id
  logQuery.pageNum = 1
  logDialog.value = true
  loadLogs()
  stopPoll()
  // 运行中每 2 秒刷新，直到出现终态
  pollTimer = window.setInterval(() => {
    if (!logs.value.some((l) => l.status === 'running')) return
    loadLogs()
  }, 2000)
}

function stopPoll() {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

async function loadLogs() {
  if (!logJobId.value) return
  logLoading.value = true
  try {
    const data: any = await request.get('/data/sync/logs', {
      params: { ...logQuery, jobId: logJobId.value }
    })
    logs.value = data.records || []
    logTotal.value = Number(data.total)
  } catch {
    /* 拦截器已提示 */
  } finally {
    logLoading.value = false
  }
}

onMounted(() => {
  load()
  loadDatasources()
  loadOdsTables()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.pager {
  margin-top: 8px;
  justify-content: flex-end;
}
.form-tip {
  width: 100%;
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-text-color-secondary);
}
</style>
