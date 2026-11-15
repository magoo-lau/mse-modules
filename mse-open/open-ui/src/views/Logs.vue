<template>
  <div class="page">
    <el-card shadow="never">
      <div class="table-toolbar">
        <div class="filters">
          <el-select v-model="query.apiCode" placeholder="API 标识" filterable clearable style="width: 200px">
            <el-option v-for="a in apiOptions" :key="a.code" :value="a.code" :label="`${a.code}（${a.name}）`" />
          </el-select>
          <el-select v-model="query.statusCode" placeholder="状态码" clearable style="width: 110px">
            <el-option v-for="code in [200, 401, 403, 404, 429, 500]" :key="code" :label="code" :value="code" />
          </el-select>
          <el-date-picker v-model="range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss"
                          start-placeholder="开始时间" end-placeholder="结束时间" style="width: 340px" />
          <el-button type="primary" icon="Search" @click="load">查询</el-button>
        </div>
        <el-button icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" border>
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="apiCode" label="API" min-width="170" class-name="mono" show-overflow-tooltip>
          <template #default="{ row }">{{ row.apiCode || '-' }}</template>
        </el-table-column>
        <el-table-column prop="method" label="方法" width="70" align="center" />
        <el-table-column prop="path" label="路径" min-width="220" class-name="mono" show-overflow-tooltip />
        <el-table-column label="状态码" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.statusCode)">{{ row.statusCode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="costMs" label="耗时(ms)" width="90" align="center" />
        <el-table-column prop="ip" label="来源 IP" width="130" class-name="mono" />
        <el-table-column prop="errorMsg" label="失败原因" min-width="200" show-overflow-tooltip />
        <el-table-column prop="traceId" label="TraceId" width="150" class-name="mono" show-overflow-tooltip />
      </el-table>

      <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import request from '../api/request'
import { Pagination } from 'mse-ui-shared/pc'

const rows = ref<any[]>([])
const total = ref(0)
const range = ref<[string, string] | null>(null)
const apiOptions = ref<any[]>([])
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  apiCode: '',
  statusCode: undefined as number | undefined
})

async function loadApiOptions() {
  try {
    apiOptions.value = await request.get('/openapp/api/list')
  } catch {
    apiOptions.value = []
  }
}

function statusType(code: number) {
  if (code >= 200 && code < 300) return 'success'
  if (code === 401 || code === 403) return 'error'
  if (code === 429) return 'warning'
  return 'info'
}

async function load() {
  const [beginTime, endTime] = range.value || [undefined, undefined]
  const page = await request.get('/openapp/log/page', {
    params: { ...query, beginTime, endTime }
  })
  rows.value = page.records
  total.value = Number(page.total ?? 0)
}

onMounted(() => {
  load()
  loadApiOptions()
})
</script>

<style scoped>
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.mono {
  font-family: Consolas, Monaco, monospace;
}
</style>
