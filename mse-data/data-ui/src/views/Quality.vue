<template>
  <el-tabs v-model="tab" type="border-card" class="tabs">
    <!-- ==================== 质量规则 ==================== -->
    <el-tab-pane label="质量规则" name="rule">
      <div class="toolbar">
        <el-input v-model="query.keyword" placeholder="规则名称" clearable style="width: 180px" @keyup.enter="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button v-permission="'data:quality:save'" type="primary" plain @click="openCreate">新建规则</el-button>
      </div>
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="name" label="规则" min-width="130" show-overflow-tooltip />
        <el-table-column label="目标" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.datasourceId === 0 ? '贴源层' : '源#' + row.datasourceId }} / {{ row.targetTable }}
          </template>
        </el-table-column>
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ typeLabel(row.ruleType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="columnName" label="检测列" width="110" show-overflow-tooltip />
        <el-table-column prop="cron" label="调度" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.cron || '手动' }}</template>
        </el-table-column>
        <el-table-column label="最近结果" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.lastPass === 1" type="success" size="small">通过 {{ row.lastPassRate ?? '-' }}%</el-tag>
            <el-tag v-else-if="row.lastPass === 0" type="danger" size="small">未通过</el-tag>
            <span v-else class="muted">未执行</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastRunTime" label="最近执行" width="150">
          <template #default="{ row }">{{ fmt(row.lastRunTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'data:quality:run'" link type="success" size="small" @click="run(row)">执行
            </el-button>
            <el-button v-permission="'data:quality:list'" link type="primary" size="small" @click="showReports(row)">
              报告
            </el-button>
            <el-button v-permission="'data:quality:save'" link type="primary" size="small" @click="openEdit(row)">编辑
            </el-button>
            <el-button v-permission="'data:quality:save'" link size="small" @click="toggle(row)">
              {{ row.status === 0 ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'data:quality:delete'" link type="danger" size="small" @click="remove(row)">删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
    </el-tab-pane>

    <!-- ==================== 检测报告 ==================== -->
    <el-tab-pane label="检测报告" name="report">
      <el-table :data="reports" size="small" v-loading="reportLoading">
        <el-table-column prop="ruleName" label="规则" min-width="130" show-overflow-tooltip />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">
              {{ row.status === 'success' ? '通过' : '未通过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalRows" label="总行数" width="90" align="right" />
        <el-table-column prop="badRows" label="坏数据" width="90" align="right" />
        <el-table-column prop="passRate" label="通过率%" width="90" align="right" />
        <el-table-column prop="costMs" label="耗时(ms)" width="90" align="right" />
        <el-table-column prop="triggerType" label="触发" width="70">
          <template #default="{ row }">{{ row.triggerType === 'manual' ? '手动' : '调度' }}</template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="说明" min-width="180" show-overflow-tooltip />
        <el-table-column prop="startTime" label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.startTime) }}</template>
        </el-table-column>
      </el-table>
      <Pagination :total="reportTotal" v-model:page="reportQuery.pageNum" v-model:size="reportQuery.pageSize" @change="loadReports" />
    </el-tab-pane>
  </el-tabs>

  <el-dialog v-model="dialog" :title="form.id ? '编辑规则' : '新建规则'" width="620px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="规则名称" required>
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="数据源" required>
        <el-select v-model="form.datasourceId" style="width: 100%" @change="onDatasourceChange">
          <el-option :value="0" label="中台贴源层（仅 ods_ 表）" />
          <el-option v-for="ds in datasources" :key="ds.id" :value="ds.id" :label="`${ds.name}（外部源）`" />
        </el-select>
      </el-form-item>
      <el-form-item label="目标表" required>
        <el-select v-model="form.targetTable" filterable allow-create placeholder="中台库需 ods_ 前缀"
          style="width: 100%" @change="loadColumnOptions">
          <el-option v-for="t in tableOptions" :key="t" :value="t" :label="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="规则类型" required>
        <el-select v-model="form.ruleType" style="width: 100%">
          <el-option label="非空检测（not_null）" value="not_null" />
          <el-option label="唯一检测（unique）" value="unique" />
          <el-option label="数值区间（range）" value="range" />
          <el-option label="枚举值（enum）" value="enum" />
          <el-option label="自定义坏数据 SQL（custom_sql）" value="custom_sql" />
          <el-option label="行数波动（row_count_fluctuation）" value="row_count_fluctuation" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="needColumn" label="检测列" required>
        <el-select v-model="form.columnName" filterable allow-create placeholder="选择或手输列名" style="width: 100%">
          <el-option v-for="c in columnOptions" :key="c" :value="c" :label="c" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.ruleType === 'range'" label="区间" required>
        <el-input v-model="rangeMin" placeholder="最小值" style="width: 140px" />
        <span class="hint">~</span>
        <el-input v-model="rangeMax" placeholder="最大值" style="width: 140px" />
      </el-form-item>
      <el-form-item v-if="form.ruleType === 'enum'" label="合法值" required>
        <el-input v-model="enumValues" placeholder="逗号分隔，如：0,1 或 A,B,C" />
      </el-form-item>
      <el-form-item v-if="form.ruleType === 'custom_sql'" label="坏数据 SQL" required>
        <el-input v-model="customSql" type="textarea" :rows="3"
          placeholder="仅 SELECT，返回的每一行视为一条坏数据，如：SELECT * FROM ods_x WHERE status = '9'" />
      </el-form-item>
      <el-form-item v-if="form.ruleType === 'row_count_fluctuation'" label="波动阈值%" required>
        <el-input-number v-model="thresholdPct" :min="1" :max="100" />
      </el-form-item>
      <el-form-item label="调度 cron">
        <cron-input v-model="form.cron" placeholder="Spring cron，留空 = 仅手动执行" preview-url="/data/sync/cron-preview" />
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
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { CronInput } from 'mse-ui-shared/pc'
import { Pagination } from 'mse-ui-shared/pc'

const tab = ref('rule')
const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const datasources = ref<any[]>([])
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '' })
const emptyForm: any = {
  id: 0, name: '', datasourceId: 0, targetTable: '', ruleType: 'not_null',
  columnName: '', ruleConfig: '', cron: '', remark: ''
}
const form = reactive<any>({ ...emptyForm })
const rangeMin = ref('')
const rangeMax = ref('')
const enumValues = ref('')
const customSql = ref('')
const thresholdPct = ref(10)

const reports = ref<any[]>([])
const reportTotal = ref(0)
const reportLoading = ref(false)
const reportQuery = reactive({ pageNum: 1, pageSize: 10 })

const TYPE_LABELS: Record<string, string> = {
  not_null: '非空', unique: '唯一', range: '区间', enum: '枚举',
  custom_sql: '自定义', row_count_fluctuation: '行数波动'
}
const typeLabel = (t: string) => TYPE_LABELS[t] || t
const needColumn = computed(() =>
  ['not_null', 'unique', 'range', 'enum'].includes(form.ruleType))

const fmt = (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '-')

async function load() {
  loading.value = true
  try {
    const data: any = await request.get('/data/quality/rule/page', { params: query })
    rows.value = data.records || []
    total.value = Number(data.total)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function loadReports() {
  reportLoading.value = true
  try {
    const data: any = await request.get('/data/quality/report/page', { params: reportQuery })
    reports.value = data.records || []
    reportTotal.value = Number(data.total)
  } catch {
    /* 拦截器已提示 */
  } finally {
    reportLoading.value = false
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

// ---------------- 元数据联动：目标表 / 检测列选项 ----------------
// datasourceId=0 为贴源层（category=ods），否则为业务源（category=source + datasourceId）
// 选项加载失败（如无 data:meta:list 权限）时静默置空：均为 allow-create，仍可手输
const tableOptions = ref<string[]>([])
const columnOptions = ref<string[]>([])
/** 检测列选项缓存，key = `${datasourceId}|${tableName}`，避免重复请求 */
const columnCache = new Map<string, string[]>()

function metaCategory(): string {
  return form.datasourceId === 0 ? 'ods' : 'source'
}

async function loadTableOptions() {
  try {
    const params: any = { pageNum: 1, pageSize: 200, category: metaCategory() }
    if (form.datasourceId !== 0) params.datasourceId = form.datasourceId
    const data: any = await request.get('/data/meta/table/page', { params })
    tableOptions.value = (data.records || []).map((r: any) => r.tableName).filter(Boolean)
  } catch {
    tableOptions.value = []
  }
}

function onDatasourceChange() {
  form.targetTable = ''
  form.columnName = ''
  columnOptions.value = []
  loadTableOptions()
}

/** 目标表变化后取检测列候选：先按表名查元数据 id，再取列清单（缓存按表名） */
async function loadColumnOptions(tableName: string) {
  columnOptions.value = []
  if (!tableName) return
  const key = `${form.datasourceId}|${tableName}`
  const cached = columnCache.get(key)
  if (cached) {
    columnOptions.value = cached
    return
  }
  try {
    const params: any = { pageNum: 1, pageSize: 50, category: metaCategory(), keyword: tableName }
    if (form.datasourceId !== 0) params.datasourceId = form.datasourceId
    const page: any = await request.get('/data/meta/table/page', { params })
    const hit = (page.records || []).find((r: any) => r.tableName === tableName)
    if (!hit) return
    const detail: any = await request.get(`/data/meta/table/${hit.id}`)
    const columns = (detail.columns || []).map((c: any) => c.columnName).filter(Boolean)
    columnCache.set(key, columns)
    // 竞态保护：表名已变时丢弃过期结果
    if (form.targetTable === tableName) columnOptions.value = columns
  } catch {
    /* 无元数据或无权限：静默置空，仍可手输 */
  }
}

function openCreate() {
  Object.assign(form, emptyForm, { id: 0 })
  rangeMin.value = ''; rangeMax.value = ''; enumValues.value = ''
  customSql.value = ''; thresholdPct.value = 10
  columnOptions.value = []
  loadTableOptions()
  dialog.value = true
}

function openEdit(row: any) {
  Object.assign(form, emptyForm, row, { id: row.id })
  let cfg: any = {}
  try {
    cfg = row.ruleConfig ? JSON.parse(row.ruleConfig) : {}
  } catch {
    cfg = {}
  }
  rangeMin.value = cfg.min ?? ''; rangeMax.value = cfg.max ?? ''
  enumValues.value = Array.isArray(cfg.values) ? cfg.values.join(',') : ''
  customSql.value = cfg.sql ?? ''; thresholdPct.value = cfg.thresholdPct ?? 10
  columnOptions.value = []
  // 编辑回显不走 @change，手动补拉表选项与检测列候选（失败静默置空）
  loadTableOptions()
  if (row.targetTable) loadColumnOptions(row.targetTable)
  dialog.value = true
}

function buildConfig(): string {
  const t = form.ruleType
  if (t === 'range') return JSON.stringify({ min: rangeMin.value, max: rangeMax.value })
  if (t === 'enum') {
    return JSON.stringify({ values: enumValues.value.split(/[,，]/).map((s: string) => s.trim()).filter(Boolean) })
  }
  if (t === 'custom_sql') return JSON.stringify({ sql: customSql.value })
  if (t === 'row_count_fluctuation') return JSON.stringify({ thresholdPct: thresholdPct.value })
  return ''
}

async function save() {
  saving.value = true
  try {
    const payload = { ...form, ruleConfig: buildConfig() }
    if (form.id) {
      await request.put('/data/quality/rule', payload)
    } else {
      await request.post('/data/quality/rule', payload)
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
  try {
    const report: any = await request.post(`/data/quality/rule/${row.id}/run`)
    if (report.status === 'success') {
      ElMessage.success(`通过：总 ${report.totalRows ?? '-'} 行，坏数据 ${report.badRows ?? 0} 行`)
    } else {
      ElMessage.warning('未通过：' + (report.errorMsg || `坏数据 ${report.badRows ?? '-'} 行`))
    }
    load()
  } catch {
    /* 拦截器已提示 */
  }
}

async function toggle(row: any) {
  await request.post(`/data/quality/rule/${row.id}/status`, null, { params: { enable: row.status !== 0 } })
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除规则「${row.name}」？历史报告将保留。`, '删除确认', { type: 'warning' })
  await request.delete(`/data/quality/rule/${row.id}`)
  ElMessage.success('已删除')
  load()
}

onMounted(() => {
  load()
  loadReports()
  loadDatasources()
})
</script>

<style scoped>
.tabs {
  min-height: 100%;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.pager {
  margin-top: 8px;
  justify-content: flex-end;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.hint {
  color: var(--el-text-color-secondary);
  margin: 0 8px;
}
</style>
