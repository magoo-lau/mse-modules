<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-button @click="load()">刷新</el-button>
        <el-tag :type="engineUp ? 'success' : 'danger'" size="small">{{ engineUp ? '引擎在线' : '引擎离线' }}</el-tag>
        <span class="hint">展示引擎中全部 {{ prefix }}-* 索引（含源已删除后遗留的索引）</span>
      </div>
      <el-table :data="rows" size="small" v-loading="loading" border stripe>
        <el-table-column type="index" label="#" width="60" />
        <el-table-column prop="indexName" label="索引名" min-width="220" show-overflow-tooltip />
        <el-table-column label="健康" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="healthType(row.health)" size="small">{{ row.health || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'open' ? 'success' : 'info'" size="small">
              {{ row.status === 'open' ? '打开' : '关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="docsCount" label="文档数" width="110" align="center" sortable />
        <el-table-column prop="storeSize" label="存储大小" width="110" align="center" />
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'search:index:list'" link type="primary" size="small" @click="openDocs(row)">查看数据</el-button>
            <el-button v-permission="'search:index:delete'" link type="warning" size="small" @click="clearIndex(row)">清空数据</el-button>
            <el-button v-permission="'search:index:delete'" link type="danger" size="small" @click="deleteIndex(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 索引数据浏览 -->
    <el-drawer v-model="drawerVisible" :title="`索引数据 - ${activeIndex}`" size="68%">
      <div class="toolbar">
        <el-input v-model="docKeyword" placeholder="按 title/content 过滤" clearable style="width: 240px" @keyup.enter="loadDocs(1)" />
        <el-button type="primary" @click="loadDocs(1)">查询</el-button>
      </div>
      <el-table :data="docs" size="small" v-loading="docsLoading" border stripe>
        <el-table-column prop="_id" label="_id" min-width="190" show-overflow-tooltip />
        <el-table-column label="title" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row._source?.title || '-' }}</template>
        </el-table-column>
        <el-table-column label="content" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row._source?.content || '-' }}</template>
        </el-table-column>
        <el-table-column label="updateTime" width="165" show-overflow-tooltip>
          <template #default="{ row }">{{ row._source?.updateTime || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="showDoc(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="docTotal" v-model:page="docPageNum" v-model:size="docPageSize" @change="loadDocs()" />
    </el-drawer>

    <!-- 文档 JSON 详情 -->
    <el-dialog v-model="docJsonVisible" :title="`文档详情 - ${activeDocId}`" width="680px">
      <pre class="doc-json">{{ docJson }}</pre>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { Pagination } from 'mse-ui-shared/pc'

const rows = ref<any[]>([])
const engineUp = ref(false)
const prefix = ref('mse-search')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data: any = await request.get('/search/index/list')
    engineUp.value = !!data.engineUp
    prefix.value = data.indexPrefix || 'mse-search'
    rows.value = data.indices || []
  } finally {
    loading.value = false
  }
}

function healthType(health: string) {
  if (health === 'green') return 'success'
  if (health === 'yellow') return 'warning'
  if (health === 'red') return 'danger'
  return 'info'
}

// ---------------- 索引数据浏览 ----------------
const drawerVisible = ref(false)
const activeIndex = ref('')
const docKeyword = ref('')
const docPageNum = ref(1)
const docPageSize = ref(10)
const docTotal = ref(0)
const docs = ref<any[]>([])
const docsLoading = ref(false)

function openDocs(row: any) {
  activeIndex.value = row.indexName
  docKeyword.value = ''
  drawerVisible.value = true
  loadDocs(1)
}

async function loadDocs(page?: number) {
  if (page) docPageNum.value = page
  docsLoading.value = true
  try {
    const data: any = await request.get('/search/index/docs', {
      params: {
        indexName: activeIndex.value,
        pageNum: docPageNum.value,
        pageSize: docPageSize.value,
        keyword: docKeyword.value || undefined
      }
    })
    docTotal.value = Number(data.total) || 0
    docs.value = data.hits || []
  } finally {
    docsLoading.value = false
  }
}

const docJsonVisible = ref(false)
const activeDocId = ref('')
const docJson = ref('')

function showDoc(row: any) {
  activeDocId.value = row._id
  docJson.value = JSON.stringify(row._source ?? row, null, 2)
  docJsonVisible.value = true
}

// ---------------- 清空 / 删除 ----------------
async function clearIndex(row: any) {
  const ok = await ElMessageBox.confirm(
    `确认清空索引 ${row.indexName} 的全部文档？索引结构保留，之后可继续写入或重建。`,
    '清空索引数据',
    { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' }
  ).then(() => true).catch(() => false)
  if (!ok) return
  const deleted: any = await request.post(`/search/index/${row.indexName}/clear`)
  ElMessage.success(`已清空 ${Number(deleted) || 0} 条文档`)
  load()
}

async function deleteIndex(row: any) {
  const res: any = await ElMessageBox.prompt(
    `此操作将删除索引 ${row.indexName} 及其全部数据，不可恢复！请输入完整索引名确认：`,
    '删除索引',
    { type: 'error', confirmButtonText: '删除', cancelButtonText: '取消' }
  ).catch(() => null)
  if (!res || res.value !== row.indexName) {
    if (res) ElMessage.warning('输入的索引名不匹配，已取消删除')
    return
  }
  await request.delete(`/search/index/${row.indexName}`)
  ElMessage.success('索引已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.page {
  padding: 12px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.doc-json {
  margin: 0;
  max-height: 60vh;
  overflow: auto;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
}
</style>
