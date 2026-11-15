<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="源标识 / 名称" clearable style="width: 220px" @keyup.enter="load(1)" />
        <el-button type="primary" @click="load(1)">查询</el-button>
        <el-button v-permission="'search:source:save'" type="primary" plain @click="openRegister">手工登记源</el-button>
        <el-button @click="load()">刷新</el-button>
      </div>

      <el-table :data="rows" size="small" v-loading="loading" border stripe>
        <el-table-column prop="sourceCode" label="源标识" min-width="160" show-overflow-tooltip />
        <el-table-column prop="sourceName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="业务域" min-width="90" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.domain" size="small">{{ row.domain }}</el-tag>
            <span v-else class="hint">不分域</span>
          </template>
        </el-table-column>
        <el-table-column prop="providerGroup" label="提供者" min-width="100" show-overflow-tooltip />
        <el-table-column prop="jumpUrlTemplate" label="跳转模板" min-width="180" show-overflow-tooltip />
        <el-table-column label="个人过滤" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.dataScopeEnabled === 1 ? 'success' : 'info'" size="small">
              {{ row.dataScopeEnabled === 1 ? '开启' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="scanCron" label="扫描 cron" min-width="110" show-overflow-tooltip />
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'danger'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'search:source:save'" link type="primary" size="small" @click="openConfig(row)">调参</el-button>
            <el-button v-permission="'search:source:field'" link type="primary" size="small" @click="openFields(row)">字段</el-button>
            <el-button v-permission="'search:source:save'" link :type="row.status === 0 ? 'danger' : 'success'" size="small" @click="toggleStatus(row)">
              {{ row.status === 0 ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="pageNum" v-model:size="pageSize" @change="load()" />
    </el-card>

    <!-- 调参对话框 -->
    <el-dialog v-model="configVisible" title="源参数配置" width="520px">
      <el-form :model="configForm" label-width="120px">
        <el-form-item label="扫描 cron">
          <cron-input v-model="configForm.scanCron" preview-url="/search/source/cron-preview" />
        </el-form-item>
        <el-form-item label="每批条数">
          <el-input-number v-model="configForm.batchSize" :min="1" :max="5000" />
        </el-form-item>
        <el-form-item label="分片数">
          <el-input-number v-model="configForm.shardCount" :min="1" :max="16" />
        </el-form-item>
        <el-form-item label="个人数据过滤">
          <el-switch v-model="configForm.dataScopeEnabled" :active-value="1" :inactive-value="0" />
          <span class="hint">开启后按当前用户角色数据范围（仅本人/本部门等）过滤该源结果</span>
        </el-form-item>
        <el-form-item label="业务域">
          <el-input v-model="configForm.domain" placeholder="空 = 不分域，仅参与「全部域」搜索" />
        </el-form-item>
        <el-form-item label="可见角色">
          <el-select
            v-model="configForm.visibleRoleList" multiple filterable clearable collapse-tags
            placeholder="不选 = 登录即可见；持有任一角色即可见" style="width: 100%">
            <el-option v-for="r in roleOptions" :key="r.code" :value="r.code" :label="`${r.name}（${r.code}）`" />
          </el-select>
          <div v-if="!roleOptions.length" class="hint">角色列表不可用（当前账号无角色管理权限），可先配置其余参数</div>
          <div v-if="configForm.permissionCode" class="hint">
            另有业务推送的权限码限制：{{ configForm.permissionCode }}（与可见角色同时生效）
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" @click="saveConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 手工登记对话框 -->
    <el-dialog v-model="registerVisible" title="手工登记源（调试通道）" width="560px">
      <el-form :model="registerForm" label-width="120px">
        <el-form-item label="源标识" required><el-input v-model="registerForm.sourceCode" placeholder="search:demo:notice" /></el-form-item>
        <el-form-item label="源名称" required><el-input v-model="registerForm.sourceName" /></el-form-item>
        <el-form-item label="业务域"><el-input v-model="registerForm.domain" placeholder="如 OA域；空 = 不分域" /></el-form-item>
        <el-form-item label="提供者分组" required><el-input v-model="registerForm.providerGroup" placeholder="业务服务名；手工调试可填 manual" /></el-form-item>
        <el-form-item label="跳转模板" required><el-input v-model="registerForm.jumpUrlTemplate" placeholder="/sub/demo/notice?id={bizId}" /></el-form-item>
        <el-form-item label="个人数据过滤"><el-switch v-model="registerForm.dataScopeEnabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 字段配置对话框 -->
    <el-dialog v-model="fieldsVisible" :title="`字段配置 - ${fieldsSource?.sourceName || ''}`" width="940px">
      <div class="field-toolbar">
        <el-tag :type="fieldsCustomized ? 'warning' : 'info'" size="small">
          {{ fieldsCustomized ? '已定制（业务推送不再覆盖）' : '编码默认（随业务推送刷新）' }}
        </el-tag>
        <el-button size="small" @click="addField">添加字段</el-button>
        <el-button size="small" :loading="sampling" @click="sampleFields">从数据采样</el-button>
        <el-button size="small" :loading="catalogLoading" @click="openCatalogImport">从业务目录导入</el-button>
        <span class="hint">字段名 = 文档 extra 键（[A-Za-z0-9_]）；text 参与加权全文检索，keyword 为全值精确匹配；保存后新文档立即可搜，存量文档需全量重建</span>
      </div>
      <el-table :data="fieldRows" size="small" border max-height="420">
        <el-table-column label="字段名" width="150">
          <template #default="{ row }"><el-input v-model="row.field" size="small" placeholder="如 status" /></template>
        </el-table-column>
        <el-table-column label="显示名" width="130">
          <template #default="{ row }"><el-input v-model="row.label" size="small" placeholder="默认同字段名" /></template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-select v-model="row.type" size="small">
              <el-option label="keyword" value="keyword" />
              <el-option label="text" value="text" />
              <el-option label="number" value="number" />
              <el-option label="date" value="date" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="可检索" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.searchable" size="small" :disabled="row.type === 'number' || row.type === 'date'" />
          </template>
        </el-table-column>
        <el-table-column label="权重" width="110" align="center">
          <template #default="{ row }">
            <el-input-number v-model="row.weight" size="small" :min="0.5" :max="10" :step="0.5" :disabled="!row.searchable" />
          </template>
        </el-table-column>
        <el-table-column label="高亮" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.highlightable" size="small" :disabled="row.type !== 'text'" />
          </template>
        </el-table-column>
        <el-table-column label="展示" width="70" align="center">
          <template #default="{ row }"><el-switch v-model="row.returned" size="small" /></template>
        </el-table-column>
        <el-table-column label="操作" width="60" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" size="small" @click="fieldRows.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="fieldsVisible = false">取消</el-button>
        <el-button type="primary" @click="saveFields">保存</el-button>
      </template>
    </el-dialog>

    <!-- 业务字段目录导入对话框（实时拉取业务 Provider，不落库） -->
    <el-dialog v-model="catalogVisible" title="从业务字段目录导入" width="760px">
      <div v-loading="catalogLoading">
        <el-alert
          v-if="catalogView && !catalogView.available"
          type="warning" :closable="false" show-icon
          :title="catalogView.message || '业务字段目录暂不可用'" />
        <template v-else-if="catalogView">
          <el-alert
            v-if="catalogView.truncated"
            type="info" :closable="false" show-icon title="目录条目过多，业务侧已截断"
            style="margin-bottom: 8px" />
          <el-table :data="catalogView.entries" size="small" border max-height="340" @selection-change="onCatalogSelect">
            <el-table-column type="selection" width="42" :selectable="(row: any) => row.status === 'available'" />
            <el-table-column prop="field" label="字段名" width="150" show-overflow-tooltip />
            <el-table-column prop="label" label="显示名" min-width="130" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" width="80" align="center" />
            <el-table-column prop="group" label="来源" min-width="170" show-overflow-tooltip />
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'platform'" type="info" size="small">已收录</el-tag>
                <el-tag v-else type="success" size="small">可导入</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="catalogView.conflicts?.length" class="conflict-block">
            <div class="hint">类型冲突（同名字段在不同业务来源中类型不一致，已排除；需业务侧统一后重试）：</div>
            <div v-for="c in catalogView.conflicts" :key="c.field" class="hint">• {{ c.field }}：{{ c.detail }}</div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="catalogVisible = false">关闭</el-button>
        <el-button type="primary" :disabled="!catalogSelection.length" @click="importCatalog">
          导入所选（{{ catalogSelection.length }}）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { CronInput } from 'mse-ui-shared/pc'
import { useAuthStore } from '../stores/auth'
import { Pagination } from 'mse-ui-shared/pc'

const keyword = ref('')
const rows = ref<any[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)

/** 可见角色候选：按登录端拉角色列表（拿不到时下拉为空，仍可配置其余参数） */
const roleOptions = ref<{ code: string; name: string }[]>([])
async function loadRoleOptions() {
  try {
    const base = useAuthStore().platform ? '/system/role/platform' : '/system/role'
    const data: any = await request.get(`${base}/page`, { params: { pageNum: 1, pageSize: 100 }, skipErrorToast: true })
    roleOptions.value = (data.records || []).map((r: any) => ({ code: String(r.code), name: r.name }))
  } catch { /* 无角色管理权限时忽略 */ }
}

const configVisible = ref(false)
const configForm = reactive<any>({})
const registerVisible = ref(false)
const registerForm = reactive<any>({ dataScopeEnabled: true })

const fieldsVisible = ref(false)
const fieldsSource = ref<any>(null)
const fieldsCustomized = ref(false)
const fieldRows = ref<any[]>([])
const sampling = ref(false)

const catalogVisible = ref(false)
const catalogLoading = ref(false)
const catalogView = ref<any>(null)
const catalogSelection = ref<any[]>([])

async function load(page?: number) {
  if (page) pageNum.value = page
  loading.value = true
  try {
    const data: any = await request.get('/search/source/page', {
      params: { pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value || undefined }
    })
    rows.value = data.records || []
    total.value = Number(data.total || 0)
  } finally {
    loading.value = false
  }
}

function openConfig(row: any) {
  Object.assign(configForm, row, {
    dataScopeEnabled: row.dataScopeEnabled ?? 1,
    scanCron: row.scanCron || '',
    // 后端逗号分隔存储 → 多选数组（保存时原样回传，未匹配选项的编码以原码展示不丢失）
    visibleRoleList: row.visibleRoles
      ? String(row.visibleRoles).split(',').map((s: string) => s.trim()).filter(Boolean)
      : []
  })
  configVisible.value = true
}

async function saveConfig() {
  if (!configForm.scanCron || !String(configForm.scanCron).trim()) {
    ElMessage.warning('扫描 cron 不能为空')
    return
  }
  await request.post('/search/source', {
    id: configForm.id,
    scanCron: configForm.scanCron,
    batchSize: configForm.batchSize,
    shardCount: configForm.shardCount,
    dataScopeEnabled: configForm.dataScopeEnabled,
    // 业务契约字段原样回传（界面不再编辑，重推时业务侧还会刷新）
    permissionCode: configForm.permissionCode,
    visibleRoles: configForm.visibleRoleList || [],
    domain: configForm.domain
  })
  ElMessage.success('已保存')
  configVisible.value = false
  load()
}

async function toggleStatus(row: any) {
  await request.put(`/search/source/${row.id}/status`, null, { params: { status: row.status === 0 ? 1 : 0 } })
  load()
}

function openRegister() {
  Object.assign(registerForm, { sourceCode: '', sourceName: '', domain: '', providerGroup: '', jumpUrlTemplate: '' })
  registerVisible.value = true
}

async function saveRegister() {
  if (!registerForm.sourceCode || !registerForm.jumpUrlTemplate) {
    ElMessage.warning('源标识与跳转模板必填')
    return
  }
  await request.post('/search/source/register', registerForm)
  ElMessage.success('已登记')
  registerVisible.value = false
  load()
}

// ==================== 字段配置 ====================

function blankField(): any {
  return { field: '', label: '', type: 'keyword', searchable: false, weight: 1, highlightable: false, returned: true }
}

async function openFields(row: any) {
  fieldsSource.value = row
  const data: any = await request.get(`/search/source/${row.id}/fields`)
  fieldsCustomized.value = !!data.customized
  fieldRows.value = (data.fields || []).map((f: any) => ({
    field: f.field, label: f.label || f.field, type: f.type || 'keyword',
    searchable: !!f.searchable, weight: f.weight ?? 1, highlightable: !!f.highlightable, returned: !!f.returned
  }))
  fieldsVisible.value = true
}

function addField() {
  fieldRows.value.push(blankField())
}

async function sampleFields() {
  sampling.value = true
  try {
    const keys: string[] = await request.get('/search/debug/sample-fields', {
      params: { sourceCode: fieldsSource.value.sourceCode, size: 30 }
    })
    const known = new Set(fieldRows.value.map((f) => f.field))
    const fresh = (keys || []).filter((k) => !known.has(k))
    if (!fresh.length) {
      ElMessage.info('采样文档中没有发现新字段键')
      return
    }
    fresh.forEach((k) => fieldRows.value.push({ ...blankField(), field: k, label: k }))
    ElMessage.success(`发现 ${fresh.length} 个候选字段，已追加（请确认类型与用法）`)
  } finally {
    sampling.value = false
  }
}

// ==================== 业务字段目录导入 ====================

async function openCatalogImport() {
  catalogVisible.value = true
  catalogLoading.value = true
  catalogView.value = null
  catalogSelection.value = []
  try {
    catalogView.value = await request.get(`/search/source/${fieldsSource.value.id}/catalog`)
  } catch {
    catalogView.value = { available: false, message: '目录拉取失败' }
  } finally {
    catalogLoading.value = false
  }
}

function onCatalogSelect(selection: any[]) {
  catalogSelection.value = selection
}

function importCatalog() {
  const known = new Set(fieldRows.value.map((f) => f.field))
  let added = 0
  for (const item of catalogSelection.value) {
    if (known.has(item.field)) continue
    fieldRows.value.push({
      field: item.field,
      label: item.label || item.field,
      type: item.type || 'keyword',
      // 导入默认值：text/keyword 参与检索（text 带高亮），可导入后调整
      searchable: item.type === 'text' || item.type === 'keyword',
      weight: 1,
      highlightable: item.type === 'text',
      returned: true
    })
    added++
  }
  catalogVisible.value = false
  if (added) ElMessage.success(`已导入 ${added} 个字段，请确认类型与用法后保存`)
  else ElMessage.info('所选字段均已存在于字段配置中')
}

async function saveFields() {
  const seen = new Set<string>()
  const payload = fieldRows.value.map((f) => ({
    field: String(f.field || '').trim(),
    label: String(f.label || '').trim() || null,
    type: f.type,
    searchable: !!f.searchable,
    weight: f.searchable ? Number(f.weight) || 1 : null,
    highlightable: !!f.highlightable,
    returned: !!f.returned
  }))
  for (const item of payload) {
    if (!item.field) {
      ElMessage.warning('存在字段名为空的行')
      return
    }
    if (!/^[A-Za-z0-9_]+$/.test(item.field)) {
      ElMessage.warning(`字段名非法（须为 [A-Za-z0-9_]）: ${item.field}`)
      return
    }
    if (seen.has(item.field)) {
      ElMessage.warning(`字段重复: ${item.field}`)
      return
    }
    seen.add(item.field)
  }
  const res: any = await request.put(`/search/source/${fieldsSource.value.id}/fields`, payload)
  fieldsVisible.value = false
  load()
  if (res?.mappingChanged) {
    ElMessageBox.confirm(
      '字段集合或类型已变化：新文档按新字段索引，但存量文档需全量重建后才能按新字段检索。是否立即创建全量重建任务（闲时窗口执行）？',
      '需要全量重建',
      { confirmButtonText: '创建重建任务', cancelButtonText: '暂不', type: 'warning' }
    )
      .then(async () => {
        await request.post('/search/task', { taskType: 'FULL_REBUILD', sourceCode: fieldsSource.value.sourceCode, idleOnly: true })
        ElMessage.success('重建任务已创建，可在「任务中心」查看进度')
      })
      .catch(() => {})
  } else {
    ElMessage.success('字段配置已保存')
  }
}

onMounted(() => {
  load(1)
  loadRoleOptions()
})
</script>

<style scoped>
.page {
  padding: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.field-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.field-toolbar .hint {
  margin-left: 0;
}
.conflict-block {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
