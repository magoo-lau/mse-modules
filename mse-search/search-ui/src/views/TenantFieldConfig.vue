<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="hint">
          为各源微调<b>本租户</b>的检索字段（可搜/权重/高亮/展示/显示名）；未覆盖的源沿用平台默认。
          可从业务字段目录<b>新增</b>平台未收录的检索字段（仅本租户生效）；系统保留字段不可配置；
          覆盖仅影响本租户的搜索行为。
        </span>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" size="small" v-loading="loading" border stripe>
        <el-table-column prop="sourceCode" label="源标识" min-width="170" show-overflow-tooltip />
        <el-table-column prop="sourceName" label="名称" min-width="120" show-overflow-tooltip />
        <el-table-column label="业务域" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.domain" size="small">{{ row.domain }}</el-tag>
            <span v-else class="hint">不分域</span>
          </template>
        </el-table-column>
        <el-table-column label="平台字段数" width="95" align="center">
          <template #default="{ row }">{{ (row.platformFields || []).length }}</template>
        </el-table-column>
        <el-table-column label="租户配置" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="(row.overrideFields || []).length" type="warning" size="small">
              已配置 {{ row.overrideFields.length }} 项
            </el-tag>
            <span v-else class="hint">沿用平台</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'search:tenantconfig:save'" link type="primary" size="small" @click="openEdit(row)">配置</el-button>
            <el-button
              v-if="(row.overrideFields || []).length"
              v-permission="'search:tenantconfig:save'"
              link type="danger" size="small" @click="reset(row)">恢复默认</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 字段覆盖/新增对话框 -->
    <el-dialog v-model="editVisible" :title="`字段配置 - ${editing?.sourceName || ''}（${editing?.sourceCode || ''}）`" width="920px">
      <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px">
        <span class="hint" style="flex: 1">
          平台字段：勾选「覆盖」后调整属性，未勾选保持平台默认；关闭「可检索」表示本租户不搜该字段。
          租户新增字段（带「新增」标记）：来自业务字段目录，取消勾选即移除。
        </span>
        <el-button size="small" :loading="catalogLoading" @click="openCatalogAdd">从业务目录添加</el-button>
      </div>
      <el-table :data="editRows" size="small" border max-height="400">
        <el-table-column label="覆盖" width="60" align="center">
          <template #default="{ row }"><el-checkbox v-model="row.enabled" /></template>
        </el-table-column>
        <el-table-column label="字段名" width="170">
          <template #default="{ row }">
            {{ row.field }}
            <el-tag v-if="row.added" type="success" size="small">新增</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="显示名" width="140">
          <template #default="{ row }">
            <el-input v-model="row.label" size="small" :placeholder="row.platformLabel" :disabled="!row.enabled" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80" align="center">
          <template #default="{ row }">{{ row.type }}</template>
        </el-table-column>
        <el-table-column label="可检索" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.searchable" size="small" :disabled="!row.enabled" />
          </template>
        </el-table-column>
        <el-table-column label="权重" width="110" align="center">
          <template #default="{ row }">
            <el-input-number v-model="row.weight" size="small" :min="0.5" :max="10" :step="0.5"
                             :disabled="!row.enabled || !row.searchable" />
          </template>
        </el-table-column>
        <el-table-column label="高亮" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.highlightable" size="small" :disabled="!row.enabled" />
          </template>
        </el-table-column>
        <el-table-column label="展示" width="70" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.returned" size="small" :disabled="!row.enabled" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存配置</el-button>
      </template>
    </el-dialog>

    <!-- 业务字段目录添加对话框（实时拉取业务 Provider，不落库） -->
    <el-dialog v-model="catalogVisible" title="从业务字段目录添加" width="760px">
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
                <el-tag v-if="row.status === 'platform'" type="info" size="small">平台已收录</el-tag>
                <el-tag v-else-if="row.status === 'tenant'" type="warning" size="small">本租户新增</el-tag>
                <el-tag v-else type="success" size="small">可添加</el-tag>
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
        <el-button type="primary" :disabled="!catalogSelection.length" @click="addCatalogFields">
          添加所选（{{ catalogSelection.length }}）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api/request'

const rows = ref<any[]>([])
const loading = ref(false)
const editVisible = ref(false)
const editing = ref<any>(null)
const editRows = ref<any[]>([])

const catalogVisible = ref(false)
const catalogLoading = ref(false)
const catalogView = ref<any>(null)
const catalogSelection = ref<any[]>([])

async function load() {
  loading.value = true
  try {
    rows.value = (await request.get('/search/tenant-field/sources')) || []
  } finally {
    loading.value = false
  }
}

function openEdit(row: any) {
  editing.value = row
  const overrides = new Map<string, any>((row.overrideFields || []).map((f: any) => [f.field, f]))
  editRows.value = (row.platformFields || []).map((f: any) => {
    const over = overrides.get(f.field)
    return {
      field: f.field,
      added: false,
      type: f.type || 'keyword',
      platformLabel: f.label || f.field,
      label: over?.label || '',
      searchable: over ? !!over.searchable : !!f.searchable,
      weight: over?.weight ?? f.weight ?? 1,
      highlightable: over ? !!over.highlightable : !!f.highlightable,
      returned: over ? !!over.returned : !!f.returned,
      enabled: !!over
    }
  })
  // 租户新增字段（携带类型且平台未声明）：整行可编辑，取消勾选即移除
  const known = new Set(editRows.value.map((r: any) => r.field))
  ;(row.overrideFields || [])
    .filter((f: any) => f.type && !known.has(f.field))
    .forEach((f: any) => {
      editRows.value.push({
        field: f.field,
        added: true,
        type: f.type,
        platformLabel: f.label || f.field,
        label: f.label || '',
        searchable: !!f.searchable,
        weight: f.weight ?? 1,
        highlightable: !!f.highlightable,
        returned: !!f.returned,
        enabled: true
      })
    })
  editVisible.value = true
}

async function saveEdit() {
  const payload = editRows.value
    .filter((r) => r.enabled)
    .map((r) => ({
      field: r.field,
      label: String(r.label || '').trim() || null,
      // 新增字段必须携带类型（mapping 并集依据）；平台字段的类型由服务端剥离
      type: r.added ? r.type : null,
      searchable: !!r.searchable,
      weight: r.searchable ? Number(r.weight) || 1 : null,
      highlightable: !!r.highlightable,
      returned: !!r.returned
    }))
  await request.put(`/search/tenant-field/${editing.value.sourceCode}`, payload)
  ElMessage.success(payload.length ? '配置已保存' : '已恢复平台默认')
  editVisible.value = false
  load()
}

async function reset(row: any) {
  await request.delete(`/search/tenant-field/${row.sourceCode}`)
  ElMessage.success('已恢复平台默认')
  load()
}

// ==================== 业务字段目录添加 ====================

async function openCatalogAdd() {
  catalogVisible.value = true
  catalogLoading.value = true
  catalogView.value = null
  catalogSelection.value = []
  try {
    catalogView.value = await request.get('/search/tenant-field/catalog', {
      params: { sourceCode: editing.value.sourceCode }
    })
  } catch {
    catalogView.value = { available: false, message: '目录拉取失败' }
  } finally {
    catalogLoading.value = false
  }
}

function onCatalogSelect(selection: any[]) {
  catalogSelection.value = selection
}

function addCatalogFields() {
  const known = new Set(editRows.value.map((r: any) => r.field))
  let added = 0
  for (const item of catalogSelection.value) {
    if (known.has(item.field)) continue
    editRows.value.push({
      field: item.field,
      added: true,
      type: item.type || 'keyword',
      platformLabel: item.label || item.field,
      label: item.label || '',
      searchable: item.type === 'text' || item.type === 'keyword',
      weight: 1,
      highlightable: item.type === 'text',
      returned: true,
      enabled: true
    })
    added++
  }
  catalogVisible.value = false
  if (added) ElMessage.success(`已添加 ${added} 个字段（保存后生效，索引就绪前可能短暂搜不到）`)
  else ElMessage.info('所选字段均已存在于配置中')
}
</script>

<style scoped>
.page {
  padding: 12px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.conflict-block {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>
