<template>
  <div class="page">
    <el-card shadow="never">
      <div class="table-toolbar">
        <div class="filters">
          <el-select v-model="query.tenantId" placeholder="租户" filterable clearable style="width: 160px" @change="onTenantChange">
            <el-option v-for="t in tenantOptions" :key="t.id" :value="t.id"
                       :label="`${t.id} · ${t.name}${t.status === 0 ? '' : ' [停用]'}`" />
          </el-select>
          <el-select v-model="query.userId" placeholder="用户" filterable clearable style="width: 180px">
            <el-option v-for="u in userOptions" :key="u.id" :value="u.id"
                       :label="`${u.nickname || u.username}（${u.username}）${u.status === 0 ? '' : ' [停用]'}`" />
          </el-select>
          <el-select v-model="query.sourceSystem" placeholder="来源系统" filterable clearable style="width: 150px">
            <el-option v-for="s in sourceOptions" :key="s" :value="s" :label="s" />
          </el-select>
          <el-button type="primary" icon="Search" @click="load">查询</el-button>
        </div>
        <el-button icon="Refresh" @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" border>
        <el-table-column label="租户" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ tenantName(row.tenantId) }}</template>
        </el-table-column>
        <el-table-column prop="userId" label="中台用户" width="170" class-name="mono" />
        <el-table-column prop="sourceSystem" label="来源系统" width="130">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.sourceSystem }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="externalUserId" label="外部用户标识" min-width="200" class-name="mono" show-overflow-tooltip />
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <el-table-column prop="createTime" label="绑定时间" width="170" />
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
const tenantOptions = ref<any[]>([])
const userOptions = ref<any[]>([])
const sourceOptions = ref<string[]>([])
const query = reactive<any>({
  pageNum: 1,
  pageSize: 10,
  tenantId: undefined,
  userId: undefined,
  sourceSystem: ''
})

/** 租户 ID 映射名称；选项缺失时回退显示 ID（Long 经 Jackson 序列化为字符串，统一按字符串比对） */
function tenantName(tenantId: any) {
  const t = tenantOptions.value.find((o: any) => String(o.id) === String(tenantId))
  return t ? t.name : tenantId
}

async function loadTenantOptions() {
  try {
    tenantOptions.value = await request.get('/openapp/app/tenant-options')
  } catch {
    // 拦截器已提示；置空仅影响租户名回显（回退显示 ID），不阻塞列表
    tenantOptions.value = []
  }
}

/** 用户选项跟随所选租户域（未选租户时按平台域 0） */
async function loadUserOptions() {
  try {
    userOptions.value = await request.get('/openapp/bind/user-options', {
      params: { tenantId: query.tenantId ?? 0 }
    })
  } catch {
    userOptions.value = []
  }
}

function onTenantChange() {
  query.userId = undefined
  loadUserOptions()
}

async function loadSourceOptions() {
  try {
    sourceOptions.value = await request.get('/openapp/bind/source-options')
  } catch {
    sourceOptions.value = []
  }
}

async function load() {
  // 空值不作为查询条件传给后端
  const params: any = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.tenantId != null && query.tenantId !== '') params.tenantId = query.tenantId
  if (query.userId != null && query.userId !== '') params.userId = query.userId
  if (query.sourceSystem) params.sourceSystem = query.sourceSystem
  const page = await request.get('/openapp/bind/page', { params })
  rows.value = page.records
  total.value = Number(page.total ?? 0)
}

onMounted(() => {
  load()
  loadTenantOptions()
  loadUserOptions()
  loadSourceOptions()
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
