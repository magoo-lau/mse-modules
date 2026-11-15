<template>
  <div class="page" v-loading="loading">
    <el-page-header @back="router.back()">
      <template #content>
        <span class="head-title">
          <span class="dot" :class="target?.status"></span>{{ target?.name || '目标详情' }}
          <el-tag size="small" type="info" style="margin-left: 8px">{{ TYPE_LABELS[target?.targetType || ''] || target?.targetType }}</el-tag>
        </span>
      </template>
    </el-page-header>

    <el-descriptions :column="4" border size="small" class="block">
      <el-descriptions-item label="地址">{{ addr }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ statusLabel }}</el-descriptions-item>
      <el-descriptions-item label="最近耗时">{{ target?.lastLatencyMs ?? '-' }} ms</el-descriptions-item>
      <el-descriptions-item label="最近采集">{{ (target?.statusTime || '').replace('T', ' ').slice(0, 19) || '-' }}</el-descriptions-item>
      <el-descriptions-item label="采集间隔">{{ target?.collectInterval ?? '-' }} 秒</el-descriptions-item>
      <el-descriptions-item label="启停">{{ target?.enabled === 0 ? '启用' : '停用' }}</el-descriptions-item>
      <el-descriptions-item label="说明" :span="2">{{ target?.remark || '-' }}</el-descriptions-item>
    </el-descriptions>

    <el-card v-if="target?.targetType === 'redis'" shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span>慢命令抓取</span>
          <el-switch :model-value="capture.capturing" :loading="captureSaving" @change="(v: string | number | boolean) => toggleCapture(!!v)" />
        </div>
      </template>
      <el-alert type="warning" :closable="false" show-icon class="capture-alert">
        抓取期间每轮采集会读取 Redis SLOWLOG（只读命令），长时开启会常驻占用采集开销与 Redis 服务端 slowlog 内存；
        抓取范围取决于 Redis 自身 slowlog-log-slower-than（默认 10ms）与 slowlog-max-len（默认 128）配置，监控只读、不修改服务端配置。排查完请及时关闭。
      </el-alert>
      <div class="capture-form">
        <el-radio-group v-model="captureMode" size="small" :disabled="capture.capturing">
          <el-radio-button value="duration">持续时长</el-radio-button>
          <el-radio-button value="range">指定起止</el-radio-button>
        </el-radio-group>
        <el-select v-if="captureMode === 'duration'" v-model="captureMinutes" size="small" style="width: 110px" :disabled="capture.capturing">
          <el-option label="30 分钟" :value="30" />
          <el-option label="1 小时" :value="60" />
          <el-option label="2 小时" :value="120" />
        </el-select>
        <el-date-picker
          v-else
          v-model="captureRange"
          type="datetimerange"
          size="small"
          value-format="YYYY-MM-DD HH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :disabled="capture.capturing"
        />
        <span v-if="capture.capturing" class="capture-state">抓取中{{ capture.endAt ? `，至 ${capture.endAt} 自动停止` : '（持续开启，手动关闭）' }}</span>
      </div>
    </el-card>

    <el-card v-if="latestMetrics.length" shadow="never" class="block">
      <template #header><span>当前指标</span></template>
      <div class="metric-grid">
        <div v-for="m in latestMetrics" :key="m.key" class="metric-item">
          <div class="metric-value">{{ m.value }}</div>
          <div class="metric-key">{{ METRIC_LABELS[m.key] || m.key }}</div>
        </div>
      </div>
    </el-card>

    <el-card v-if="latestSlowCmds.length" shadow="never" class="block">
      <template #header><span>最近慢命令</span></template>
      <el-table :data="latestSlowCmds" size="small">
        <el-table-column prop="time" label="时间" width="150" />
        <el-table-column label="耗时" width="90">
          <template #default="{ row }"><span class="slow-ms">{{ row.elapsedMs }} ms</span></template>
        </el-table-column>
        <el-table-column prop="command" label="命令" width="110" />
        <el-table-column prop="key" label="Key" min-width="160" show-overflow-tooltip />
        <el-table-column prop="args" label="参数" min-width="260" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card v-if="latestSlowSqls.length" shadow="never" class="block">
      <template #header><span>最近慢 SQL</span></template>
      <el-table :data="latestSlowSqls" size="small">
        <el-table-column prop="time" label="时间" width="150" />
        <el-table-column label="耗时" width="90">
          <template #default="{ row }"><span class="slow-ms">{{ row.elapsedMs }} ms</span></template>
        </el-table-column>
        <el-table-column prop="mapperId" label="Mapper" min-width="200" show-overflow-tooltip />
        <el-table-column prop="sql" label="SQL" min-width="320" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-head">
          <span>趋势</span>
          <el-radio-group v-model="hours" size="small">
            <el-radio-button :value="1">近 1 小时</el-radio-button>
            <el-radio-button :value="24">近 24 小时</el-radio-button>
            <el-radio-button :value="168">近 7 天</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <div ref="latencyChartEl" class="chart" style="height: 220px"></div>
      <div v-if="hasMetricSeries" ref="metricChartEl" class="chart" style="height: 260px"></div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  getSlowCapture, getTarget, listRecords, setSlowCapture,
  type MonRecord, type MonTarget, type SlowCaptureStatus
} from '../api/monitor'

const route = useRoute()
const router = useRouter()

const TYPE_LABELS: Record<string, string> = {
  service: '微服务', http: 'HTTP', jvm: 'JVM', slowsql: '慢SQL', mysql: 'MySQL', redis: 'Redis', rocketmq: 'RocketMQ', os: '操作系统'
}
const METRIC_LABELS: Record<string, string> = {
  health: '健康状态', status: '集群状态',
  cpu_usage_pct: 'CPU 使用率(%)', mem_usage_pct: '内存使用率(%)', disk_usage_pct: '磁盘使用率(%)',
  disk_detail: '磁盘明细', mem_total_gb: '内存总量(GB)', mem_used_gb: '已用内存(GB)',
  net_rx_kbps: '收(KB/s)', net_tx_kbps: '发(KB/s)', uptime_hours: '运行时长(小时)',
  heap_used_mb: '堆已用(MB)', heap_max_mb: '堆上限(MB)', heap_usage_pct: '堆使用率(%)',
  nonheap_used_mb: '非堆(MB)', threads_live: '线程数', gc_pause_count: 'GC次数/间隔',
  gc_pause_time_ms: 'GC耗时(ms)/间隔', process_cpu_pct: '进程CPU(%)',
  slowsql_enabled: '慢SQL采集', slowsql_total: '慢SQL累计', slowsql_rate: '慢SQL新增/间隔',
  slowcmd_capturing: '慢命令抓取', slowcmd_len: 'SLOWLOG条数',
  threads_connected: '连接数', max_connections: '最大连接', qps: 'QPS',
  used_memory_mb: '已用内存(MB)', maxmemory_mb: '内存上限(MB)', connected_clients: '客户端数',
  ops_per_sec: 'OPS', hit_rate_pct: '命中率(%)', redis_version: '版本',
  broker_count: 'Broker 数', cluster_count: '集群数', net_note: ''
}

const target = ref<MonTarget | null>(null)
const records = ref<MonRecord[]>([])
const hours = ref(24)
const loading = ref(false)

const latencyChartEl = ref<HTMLDivElement>()
const metricChartEl = ref<HTMLDivElement>()
let latencyChart: echarts.ECharts | null = null
let metricChart: echarts.ECharts | null = null

/** 按类型参与趋势图的核心指标键 */
const METRIC_SERIES: Record<string, string[]> = {
  os: ['cpu_usage_pct', 'mem_usage_pct', 'disk_usage_pct'],
  mysql: ['threads_connected', 'qps'],
  redis: ['mem_usage_pct', 'hit_rate_pct'],
  rocketmq: ['broker_count'],
  http: ['status'],
  jvm: ['heap_usage_pct', 'process_cpu_pct', 'threads_live'],
  slowsql: ['slowsql_rate'],
  service: []
}

/** 最近慢 SQL 行（最新采样 slow_sqls JSON） */
interface SlowSqlItem {
  time?: string
  elapsedMs?: number
  mapperId?: string
  sql?: string
}

/** 最近慢命令行（最新采样 slow_cmds JSON，抓取开启期间产生） */
interface SlowCmdItem {
  time?: string
  elapsedMs?: number
  command?: string
  key?: string
  args?: string
}

const addr = computed(() => {
  const t = target.value
  if (!t) return '-'
  return t.serviceUrl || (t.host ? `${t.host}:${t.port ?? ''}` : '本机')
})
const statusLabel = computed(() =>
  target.value?.status === 'up' ? '正常' : target.value?.status === 'down' ? '异常' : '未采集')

function parseMetrics(record: MonRecord): Record<string, unknown> {
  if (!record.metrics) return {}
  try {
    return JSON.parse(record.metrics) as Record<string, unknown>
  } catch {
    return {}
  }
}

const latestMetrics = computed(() => {
  const latest = records.value.length ? records.value[records.value.length - 1] : null
  const metrics = latest ? parseMetrics(latest) : {}
  return Object.entries(metrics)
    .filter(([key]) => METRIC_LABELS[key])
    .map(([key, value]) => ({ key, value: String(value) }))
})

const latestSlowSqls = computed<SlowSqlItem[]>(() => {
  const latest = records.value.length ? records.value[records.value.length - 1] : null
  if (!latest) return []
  const metrics = parseMetrics(latest)
  if (typeof metrics.slow_sqls !== 'string') return []
  try {
    const list = JSON.parse(metrics.slow_sqls) as SlowSqlItem[]
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
})

const latestSlowCmds = computed<SlowCmdItem[]>(() => {
  const latest = records.value.length ? records.value[records.value.length - 1] : null
  if (!latest) return []
  const metrics = parseMetrics(latest)
  if (typeof metrics.slow_cmds !== 'string') return []
  try {
    const list = JSON.parse(metrics.slow_cmds) as SlowCmdItem[]
    return Array.isArray(list) ? list : []
  } catch {
    return []
  }
})

// ---------------- 慢命令抓取（Redis 目标） ----------------
const capture = ref<SlowCaptureStatus>({ capturing: false, enabled: false })
const captureMode = ref<'duration' | 'range'>('duration')
const captureMinutes = ref(30)
const captureRange = ref<[string, string] | null>(null)
const captureSaving = ref(false)

async function loadCapture() {
  if (target.value?.targetType !== 'redis') return
  try {
    capture.value = await getSlowCapture(target.value.id)
  } catch {
    // 开关状态加载失败不阻塞详情页
  }
}

async function toggleCapture(on: boolean) {
  if (!target.value) return
  if (on && captureMode.value === 'range' && !captureRange.value) {
    ElMessage.warning('请先选择起止时间')
    return
  }
  captureSaving.value = true
  try {
    if (on) {
      const data = captureMode.value === 'duration'
        ? { enabled: true, minutes: captureMinutes.value }
        : { enabled: true, startAt: captureRange.value?.[0], endAt: captureRange.value?.[1] }
      capture.value = await setSlowCapture(target.value.id, data)
      ElMessage.success('慢命令抓取已开启')
    } else {
      capture.value = await setSlowCapture(target.value.id, { enabled: false })
      ElMessage.success('慢命令抓取已关闭')
    }
    load()
  } finally {
    captureSaving.value = false
  }
}

const hasMetricSeries = computed(() => {
  const keys = METRIC_SERIES[target.value?.targetType || ''] || []
  if (!keys.length) return false
  const latest = records.value.length ? parseMetrics(records.value[records.value.length - 1]) : {}
  return keys.some((key) => latest[key] != null && key in latest)
})

function timeOf(record: MonRecord): string {
  return (record.collectTime || '').replace('T', ' ').slice(5, 16)
}

function renderCharts() {
  const times = records.value.map(timeOf)
  if (latencyChartEl.value) {
    latencyChart = latencyChart || echarts.init(latencyChartEl.value)
    latencyChart.setOption({
      grid: { left: 48, right: 16, top: 30, bottom: 24 },
      tooltip: { trigger: 'axis' },
      legend: { top: 0, left: 'center', type: 'scroll' },
      xAxis: { type: 'category', data: times, axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', name: 'ms', axisLabel: { fontSize: 10 } },
      series: [
        {
          name: '响应耗时(ms)',
          type: 'line',
          showSymbol: false,
          data: records.value.map((r) => (r.latencyMs == null ? null : r.latencyMs)),
          lineStyle: { width: 1.5 },
          areaStyle: { opacity: 0.08 }
        }
      ]
    })
  }
  const keys = (METRIC_SERIES[target.value?.targetType || ''] || []).filter((key) => key !== 'status')
  if (metricChartEl.value && keys.length && hasMetricSeries.value) {
    metricChart = metricChart || echarts.init(metricChartEl.value)
    const series = keys
      .map((key) => ({
        name: METRIC_LABELS[key] || key,
        type: 'line' as const,
        showSymbol: false,
        data: records.value.map((r) => {
          const value = parseMetrics(r)[key]
          return typeof value === 'number' ? value : null
        }),
        lineStyle: { width: 1.5 }
      }))
    metricChart.setOption({
      grid: { left: 48, right: 16, top: 30, bottom: 24 },
      tooltip: { trigger: 'axis' },
      legend: { top: 0, left: 'center', type: 'scroll' },
      xAxis: { type: 'category', data: times, axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
      series
    })
  }
}

async function load() {
  loading.value = true
  try {
    target.value = await getTarget(route.params.id as string)
    records.value = await listRecords(route.params.id as string, hours.value)
    await nextTick()
    renderCharts()
  } finally {
    loading.value = false
  }
  loadCapture()
}

function handleResize() {
  latencyChart?.resize()
  metricChart?.resize()
}

onMounted(() => {
  load()
  window.addEventListener('resize', handleResize)
})
onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  latencyChart?.dispose()
  metricChart?.dispose()
})
watch(hours, load)
</script>

<style scoped>
.page {
  padding: 12px;
}
.head-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
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
.block {
  margin-top: 12px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}
.metric-item {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 8px 12px;
}
.metric-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  word-break: break-all;
}
.metric-key {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.slow-ms {
  color: var(--el-color-danger);
  font-weight: 600;
}
.capture-alert {
  margin-bottom: 10px;
}
.capture-form {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.capture-state {
  font-size: 12px;
  color: var(--el-color-warning);
}
</style>
