<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="关键词" clearable style="width: 220px" @keyup.enter="search()" />
        <el-button type="primary" @click="search()">查询</el-button>
        <el-button @click="load()">刷新</el-button>
      </div>
      <el-table :data="rows" size="small" v-loading="loading" border stripe>
        <el-table-column type="index" label="#" width="60" :index="indexOffset" />
        <el-table-column prop="keyword" label="关键词" min-width="200" />
        <el-table-column prop="hitCount" label="命中次数" width="120" align="center" sortable />
        <el-table-column prop="lastSearchTime" label="最近搜索时间" width="180" />
      </el-table>
      <Pagination :total="total" v-model:page="pageNum" v-model:size="pageSize" @change="load()" />
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <template #header>各源索引文档量</template>
      <el-table :data="indexStats" size="small" v-loading="statsLoading" border stripe>
        <el-table-column prop="sourceCode" label="源标识" min-width="180" />
        <el-table-column prop="sourceName" label="名称" min-width="140" />
        <el-table-column prop="indexName" label="索引" min-width="200" />
        <el-table-column label="引擎" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.engineUp ? 'success' : 'danger'" size="small">{{ row.engineUp ? '在线' : '离线' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文档数" width="120" align="center">
          <template #default="{ row }">{{ row.docCount >= 0 ? row.docCount : '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import request from '../api/request'
import { Pagination } from 'mse-ui-shared/pc'

const keyword = ref('')
const rows = ref<any[]>([])
const indexStats = ref<any[]>([])
const loading = ref(false)
const statsLoading = ref(false)
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)

/** 榜单序号跨页连续（第 2 页从 21 开始） */
const indexOffset = (i: number) => (pageNum.value - 1) * pageSize.value + i + 1

async function load() {
  loading.value = true
  try {
    const data = await request.get('/search/stats/hot', {
      params: { pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value || undefined }
    }) as any
    rows.value = data?.records ?? []
    total.value = Number(data?.total ?? 0)
  } finally {
    loading.value = false
  }
}

/** 关键词查询：重置到第一页 */
function search() {
  pageNum.value = 1
  load()
}

async function loadStats() {
  statsLoading.value = true
  try {
    indexStats.value = (await request.get('/search/stats/index')) as any[]
  } finally {
    statsLoading.value = false
  }
}

onMounted(() => {
  load()
  loadStats()
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
</style>
