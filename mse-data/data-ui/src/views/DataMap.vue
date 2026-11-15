<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="表名/注释关键字" clearable style="width: 200px" @keyup.enter="load" />
      <el-select v-model="query.category" placeholder="分类" clearable style="width: 130px">
        <el-option label="源库表" value="source" />
        <el-option label="贴源表（ODS）" value="ods" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button v-permission="'data:meta:collect'" type="primary" plain :loading="collecting" @click="collectOds">
        登记贴源层
      </el-button>
      <el-button v-permission="'data:meta:collect'" type="primary" plain :loading="collecting"
        @click="collectMasterSource">
        登记中台库业务表
      </el-button>
      <el-select v-model="collectDsId" placeholder="采集指定数据源…" style="width: 220px" clearable
        v-permission="'data:meta:collect'">
        <el-option v-for="ds in datasources" :key="ds.id" :value="ds.id" :label="ds.name" />
      </el-select>
      <el-button v-permission="'data:meta:collect'" :disabled="!collectDsId" :loading="collecting" @click="collectDs">
        采集
      </el-button>
    </div>

    <el-table :data="rows" size="small" v-loading="loading">
      <el-table-column prop="tableName" label="表名" min-width="180" show-overflow-tooltip />
      <el-table-column prop="tableComment" label="注释" min-width="160" show-overflow-tooltip />
      <el-table-column label="分类" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.category === 'ods' ? 'warning' : 'info'">
            {{ row.category === 'ods' ? '贴源层' : '源库' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="tableSchema" label="库/Schema" width="120" show-overflow-tooltip />
      <el-table-column prop="collectedTime" label="采集时间" width="150">
        <template #default="{ row }">{{ fmt(row.collectedTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'data:meta:list'" link type="primary" size="small" @click="detail(row)">详情</el-button>
          <el-button v-permission="'data:meta:list'" link size="small" @click="preview(row)">采样</el-button>
          <el-button v-permission="'data:meta:export'" link type="success" size="small" :loading="row._exporting"
            @click="exportRows(row)">导出</el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
  </el-card>

  <el-drawer v-model="drawer" :title="`表详情：${current?.tableName || ''}`" size="55%">
    <template v-if="current">
      <el-descriptions :column="2" size="small" border class="mb8">
        <el-descriptions-item label="表名">{{ current.table.tableName }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ current.table.category === 'ods' ? '贴源层' : '源库' }}</el-descriptions-item>
        <el-descriptions-item label="注释">{{ current.table.tableComment || '-' }}</el-descriptions-item>
        <el-descriptions-item label="采集时间">{{ fmt(current.table.collectedTime) }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="current.columns" size="small" max-height="300">
        <el-table-column prop="ordinal" label="#" width="50" />
        <el-table-column prop="columnName" label="列名" min-width="140" show-overflow-tooltip />
        <el-table-column prop="dataType" label="类型" width="130" />
        <el-table-column label="可空" width="60">
          <template #default="{ row }">{{ row.nullable === 1 ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="columnComment" label="注释" min-width="140" show-overflow-tooltip />
      </el-table>

      <div class="section-title">上下游血缘</div>
      <el-row :gutter="8">
        <el-col :span="12">
          <div class="lineage-title">上游来源（同步自）</div>
          <el-table :data="lineage?.upstream || []" size="small" max-height="160">
            <el-table-column prop="sourceTable" label="来源表" min-width="120" show-overflow-tooltip />
            <el-table-column prop="jobName" label="同步任务" min-width="110" show-overflow-tooltip />
          </el-table>
        </el-col>
        <el-col :span="12">
          <div class="lineage-title">下游去向（被同步至）</div>
          <el-table :data="lineage?.downstream || []" size="small" max-height="160">
            <el-table-column prop="targetTable" label="目标表" min-width="120" show-overflow-tooltip />
            <el-table-column prop="jobName" label="同步任务" min-width="110" show-overflow-tooltip />
          </el-table>
        </el-col>
      </el-row>

      <template v-if="sample">
        <div class="section-title">采样预览（前 {{ sample.rows.length }} 行）</div>
        <el-table v-if="sample.rows.length" :data="sample.rows" size="small" max-height="300" border>
          <el-table-column v-for="col in sample.columns" :key="col" :prop="col" :label="col" min-width="120"
            show-overflow-tooltip />
        </el-table>
        <el-empty v-else description="表暂无数据" :image-size="60" />
      </template>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api/request'
import { useAuthStore } from '../stores/auth'
import { Pagination } from 'mse-ui-shared/pc'

const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const collecting = ref(false)
const collectDsId = ref<number | undefined>()
const datasources = ref<any[]>([])
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', category: '' })
const drawer = ref(false)
const current = ref<any>(null)
const sample = ref<any>(null)
const lineage = ref<any>(null)

function fmt(value: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

async function load() {
  loading.value = true
  try {
    const data: any = await request.get('/data/meta/table/page', { params: query })
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

async function collectOds() {
  collecting.value = true
  try {
    const count: number = await request.post('/data/meta/collect')
    ElMessage.success(`贴源层登记完成，共 ${count} 张表`)
    load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    collecting.value = false
  }
}

async function collectMasterSource() {
  collecting.value = true
  try {
    const count: number = await request.post('/data/meta/collect', null, { params: { datasourceId: 0 } })
    ElMessage.success(`中台库业务表登记完成，共 ${count} 张表`)
    load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    collecting.value = false
  }
}

async function collectDs() {
  if (!collectDsId.value) return
  collecting.value = true
  try {
    const count: number = await request.post('/data/meta/collect', null, {
      params: { datasourceId: collectDsId.value }
    })
    ElMessage.success(`采集完成，共 ${count} 张表`)
    load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    collecting.value = false
  }
}

async function detail(row: any) {
  try {
    current.value = await request.get(`/data/meta/table/${row.id}`)
    sample.value = null
    lineage.value = await request.get('/data/sync/lineage', { params: { tableName: row.tableName } })
    drawer.value = true
  } catch {
    /* 拦截器已提示 */
  }
}

async function exportRows(row: any) {
  row._exporting = true
  try {
    // 文件下载带 token，走独立 axios 实例（拦截器按 JSON 解析会破坏二进制流）
    const axios = (await import('axios')).default
    const resp = await axios.get(`/api/data/meta/table/${row.id}/export`, {
      params: { limit: 10000 },
      responseType: 'blob',
      headers: { Authorization: useAuthStore().token }
    })
    const url = URL.createObjectURL(resp.data)
    const a = document.createElement('a')
    a.href = url
    a.download = `${row.tableName}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('导出失败')
  } finally {
    row._exporting = false
  }
}

async function preview(row: any) {
  try {
    await detail(row)
    sample.value = await request.get(`/data/meta/table/${row.id}/sample`)
  } catch {
    /* 拦截器已提示 */
  }
}

onMounted(() => {
  load()
  loadDatasources()
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
.mb8 {
  margin-bottom: 8px;
}
.section-title {
  font-weight: 600;
  margin: 12px 0 8px;
}
.lineage-title {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-bottom: 4px;
}
</style>
