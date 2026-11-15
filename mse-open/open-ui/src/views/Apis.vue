<template>
  <div class="page">
    <el-card shadow="never">
      <div class="table-toolbar">
        <div class="left">
          <el-button icon="Refresh" @click="load">刷新</el-button>
          <span class="hint">
            目录条目由业务积木经 open-api 契约自动注册（source=rpc）+ SQL 种子内置（source=seed），
            每租户一份目录；这里只做本租户的启停治理（不影响其他租户与平台域）；应用授权在「应用管理 → 授权」。
          </span>
        </div>
        <div class="filters">
          <el-input v-model="query.keyword" placeholder="标识 / 名称 / 路径" clearable style="width: 200px"
                    @keyup.enter="load" @clear="load" />
          <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px" @change="load">
            <el-option label="启用" :value="0" />
            <el-option label="停用" :value="1" />
          </el-select>
          <el-button type="primary" icon="Search" @click="load">查询</el-button>
        </div>
      </div>

      <el-table :data="filtered" border>
        <el-table-column prop="code" label="API 标识" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.code }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column label="方法" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="methodTag(row.httpMethod)">{{ row.httpMethod }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="path" label="对外路径" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.source === 'rpc' ? 'success' : 'info'">
              {{ row.source === 'rpc' ? '自注册' : '内置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分发目标" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.target" class="mono">{{ row.target }}</span>
            <span v-else class="dim">open-service 内置端点</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-permission="'open:api:status'" :model-value="row.status === 0"
                       @change="changeStatus(row, $event)" />
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" min-width="120" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api/request'

const rows = ref<any[]>([])
const query = ref<{ keyword: string; status: number | null }>({ keyword: '', status: null })

const filtered = computed(() =>
  rows.value.filter((row) => {
    if (query.value.status !== null && query.value.status !== undefined && row.status !== query.value.status) return false
    const keyword = query.value.keyword.trim().toLowerCase()
    if (!keyword) return true
    return [row.code, row.name, row.path, row.target].some((text: string) =>
      text && text.toLowerCase().includes(keyword)
    )
  })
)

async function load() {
  rows.value = await request.get('/openapp/api/list')
}

async function changeStatus(row: any, enabled: boolean) {
  const status = enabled ? 0 : 1
  await request.put(`/openapp/api/${row.id}/status`, { status })
  row.status = status
  ElMessage.success(enabled ? '已启用' : '已停用（本租户即时下线，其他租户与平台域不受影响）')
}

function methodTag(method: string) {
  const map: Record<string, string> = { GET: 'success', POST: 'primary', PUT: 'warning', DELETE: 'danger', ALL: 'info' }
  return (map[method?.toUpperCase()] ?? 'info') as any
}

onMounted(load)
</script>

<style scoped>
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
}
.mono {
  font-family: Consolas, Monaco, monospace;
}
.dim {
  color: var(--el-text-color-secondary);
}
</style>
