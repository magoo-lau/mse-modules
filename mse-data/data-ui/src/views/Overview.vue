<template>
  <div class="page">
    <el-row :gutter="8">
      <el-col v-for="card in cards" :key="card.label" :span="3">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value">{{ card.value ?? '-' }}</div>
          <div class="stat-label">{{ card.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="8" class="mt8">
      <el-col :span="12">
        <el-card shadow="never" header="最近同步任务">
          <el-table :data="overview?.recentSyncLogs || []" size="small">
            <el-table-column prop="name" label="任务" min-width="140" show-overflow-tooltip />
            <el-table-column prop="rowCount" label="最近行数" width="100" align="right" />
            <el-table-column prop="lastRunTime" label="最近执行" width="160">
              <template #default="{ row }">{{ fmt(row.lastRunTime) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never" header="最近 API 调用">
          <el-table :data="overview?.recentApiLogs || []" size="small">
            <el-table-column prop="apiId" label="API" width="80" />
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">
                  {{ row.status === 'success' ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="rowCount" label="行数" width="70" align="right" />
            <el-table-column prop="costMs" label="耗时(ms)" width="90" align="right" />
            <el-table-column prop="createTime" label="时间" width="160">
              <template #default="{ row }">{{ fmt(row.createTime) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import request from '../api/request'

const overview = ref<any>(null)

const cards = computed(() => [
  { label: '注册数据源', value: overview.value?.datasourceCount },
  { label: '启用中同步任务', value: overview.value?.enabledJobCount },
  { label: '贴源层表（ODS）', value: overview.value?.odsTableCount },
  { label: '已采集源表', value: overview.value?.sourceTableCount },
  { label: '已发布数据 API', value: overview.value?.publishedApiCount },
  { label: '今日调用次数', value: overview.value?.todayApiCalls },
  { label: '质量规则', value: overview.value?.qualityRuleCount },
  { label: '未通过规则', value: overview.value?.qualityBadRuleCount }
])

function fmt(value: string) {
  return value ? value.replace('T', ' ').slice(0, 19) : '-'
}

onMounted(async () => {
  try {
    overview.value = await request.get('/data/overview')
  } catch {
    /* 拦截器已提示 */
  }
})
</script>

<style scoped>
.page {
  padding: 8px;
}
.stat-card {
  text-align: center;
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
}
.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin-top: 4px;
}
.mt8 {
  margin-top: 8px;
}
</style>
