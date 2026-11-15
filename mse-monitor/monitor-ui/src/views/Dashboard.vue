<template>
  <div class="page">
    <el-row :gutter="8" class="stat-row">
      <el-col :span="4"><div class="stat-card"><div class="stat-num">{{ s.total }}</div><div class="stat-label">监控目标</div></div></el-col>
      <el-col :span="4"><div class="stat-card ok"><div class="stat-num">{{ s.up }}</div><div class="stat-label">正常</div></div></el-col>
      <el-col :span="4"><div class="stat-card bad"><div class="stat-num">{{ s.down }}</div><div class="stat-label">异常</div></div></el-col>
      <el-col :span="4"><div class="stat-card"><div class="stat-num">{{ s.unknown }}</div><div class="stat-label">未知</div></div></el-col>
      <el-col :span="4"><div class="stat-card"><div class="stat-num">{{ s.disabled }}</div><div class="stat-label">已停用</div></div></el-col>
      <el-col :span="4"><div class="stat-card warn"><div class="stat-num">{{ s.firingAlerts }}</div><div class="stat-label">告警中</div></div></el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span>监控目标状态</span>
          <el-button text type="primary" @click="router.push('/targets')">管理目标</el-button>
        </div>
      </template>
      <el-empty v-if="!s.targets.length" description="暂无启用的监控目标，请到「监控目标」页新增或启用" />
      <div v-else class="target-grid">
        <div v-for="t in s.targets" :key="t.id" class="target-card" @click="router.push(`/targets/${t.id}`)">
          <div class="target-head">
            <span class="dot" :class="t.status"></span>
            <span class="target-name" :title="t.name">{{ t.name }}</span>
            <el-tag size="small" type="info">{{ typeLabel(t.targetType) }}</el-tag>
          </div>
          <div class="target-line">{{ targetAddr(t) }}</div>
          <div class="target-line muted">
            <span>{{ t.status === 'up' ? `耗时 ${t.lastLatencyMs ?? '-'} ms` : statusLabel(t.status) }}</span>
            <span v-if="t.statusTime">{{ shortTime(t.statusTime) }}</span>
          </div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span>最近告警</span></template>
      <el-table :data="s.recentAlerts" size="small" empty-text="暂无告警">
        <el-table-column prop="targetName" label="目标" min-width="120" />
        <el-table-column label="级别" width="70">
          <template #default="{ row }">
            <el-tag size="small" :type="row.level === 'error' ? 'danger' : 'warning'">{{ row.level === 'error' ? '严重' : '警告' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'firing' ? 'danger' : 'success'">{{ row.status === 'firing' ? '触发中' : '已恢复' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastTriggerTime" label="最近触发" width="160" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { dashboardSummary, type DashboardSummary } from '../api/monitor'

const router = useRouter()
const s = reactive<DashboardSummary>({
  total: 0, up: 0, down: 0, unknown: 0, disabled: 0, firingAlerts: 0, targets: [], recentAlerts: []
})

const TYPE_LABELS: Record<string, string> = {
  service: '微服务', http: 'HTTP', mysql: 'MySQL', redis: 'Redis', rocketmq: 'RocketMQ', os: '操作系统'
}
const typeLabel = (t: string) => TYPE_LABELS[t] || t
const statusLabel = (st: string) => (st === 'down' ? '探测失败' : st === 'up' ? '正常' : '未采集')
const targetAddr = (t: { targetType: string; serviceUrl?: string; host?: string; port?: number }) =>
  t.serviceUrl || (t.host ? `${t.host}${t.port ? ':' + t.port : ''}` : '本机')
const shortTime = (time: string) => (time || '').replace('T', ' ').slice(5, 16)

async function refresh() {
  try {
    Object.assign(s, await dashboardSummary())
  } catch {
    /* 请求失败已由拦截器提示 */
  }
}

onMounted(() => {
  refresh()
  const timer = setInterval(refresh, 30_000)
  onUnmounted(() => clearInterval(timer))
})
</script>

<style scoped>
.page {
  padding: 12px;
}
.stat-row {
  margin-bottom: 12px;
}
.stat-card {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 12px 16px;
  text-align: center;
}
.stat-num {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.stat-card.ok .stat-num {
  color: var(--el-color-success);
}
.stat-card.bad .stat-num {
  color: var(--el-color-danger);
}
.stat-card.warn .stat-num {
  color: var(--el-color-warning);
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.block {
  margin-bottom: 12px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.target-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 8px;
}
.target-card {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color 0.2s;
}
.target-card:hover {
  border-color: var(--el-color-primary);
}
.target-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.target-name {
  font-weight: 500;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot.up {
  background: var(--el-color-success);
}
.dot.down {
  background: var(--el-color-danger);
}
.dot.unknown {
  background: var(--el-text-color-disabled);
}
.target-line {
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.target-line.muted {
  color: var(--el-text-color-secondary);
  display: flex;
  justify-content: space-between;
}
</style>
