<template>
  <div class="dataset-card">
    <div class="dataset-head">
      <span class="dataset-title">📊 查询结果</span>
      <el-tag size="small" effect="plain">{{ dataset.rowCount }} 行</el-tag>
      <el-tag v-if="dataset.hasMore" size="small" type="warning" effect="plain">仅展示前 {{ dataset.rows.length }} 行</el-tag>
      <div class="dataset-ops">
        <el-select v-if="chartable" v-model="chartType" size="small" style="width: 90px">
          <el-option label="柱状图" value="bar" />
          <el-option label="条形图" value="barH" />
          <el-option label="折线图" value="line" />
          <el-option label="饼图" value="pie" />
        </el-select>
        <el-radio-group v-if="chartable" v-model="view" size="small">
          <el-radio-button value="table">表格</el-radio-button>
          <el-radio-button value="chart">图表</el-radio-button>
        </el-radio-group>
      </div>
    </div>
    <el-collapse v-if="dataset.sql" class="dataset-sql">
      <el-collapse-item title="执行的 SQL" name="sql">
        <code class="dataset-sql-code">{{ dataset.sql }}</code>
      </el-collapse-item>
    </el-collapse>
    <el-table
      v-if="view === 'table' || !chartable"
      :data="dataset.rows"
      size="small"
      max-height="300"
      :formatter="formatCell"
      style="width: 100%"
    >
      <el-table-column v-for="col in dataset.columns" :key="col" :prop="col" :label="col" min-width="90" show-overflow-tooltip />
    </el-table>
    <div v-else ref="chartEl" class="dataset-chart"></div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { DatasetPayload } from '../api/ai'

/**
 * 工具查询结果卡片：数据表格 + 图表双视图。
 * 图表启发式：非数值列为维度（取首个），数值列为度量（≤3 个系列）；
 * 维度名/取值呈时间特征默认折线，否则柱状；单行结果（如 COUNT 总数）不提供图表。
 */
echarts.use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
const props = defineProps<{ dataset: DatasetPayload }>()

type ChartKind = 'bar' | 'barH' | 'line' | 'pie'
const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#9a7fd1']

const view = ref<'table' | 'chart'>('table')
const chartType = ref<ChartKind>('bar')
const chartEl = ref<HTMLElement>()
let chart: ReturnType<typeof echarts.init> | null = null

function isNumeric(v: any): boolean {
  if (typeof v === 'number') return Number.isFinite(v)
  if (typeof v === 'string' && v.trim() !== '') return Number.isFinite(Number(v))
  return false
}

function numOf(v: any): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

const measures = computed(() =>
  props.dataset.columns.filter((col) => {
    const values = props.dataset.rows.map((r) => r[col]).filter((v) => v !== null && v !== undefined && v !== '')
    return values.length > 0 && values.filter(isNumeric).length >= values.length / 2
  })
)
const dimensions = computed(() => props.dataset.columns.filter((col) => !measures.value.includes(col)))
const dim = computed(() => dimensions.value[0])
const chartable = computed(() => !!dim.value && measures.value.length > 0 && props.dataset.rows.length >= 2)

// 默认图型：维度名或取值呈时间特征 → 折线，否则柱状
const temporalRe = /(date|time|year|month|day|quarter|日期|时间|年|月|日|季度)/i
const temporalValueRe = /^\d{4}[-/.年]\d{1,2}([-/月.]\d{1,2}日?)?/
const temporal = computed(() => {
  if (!dim.value) return false
  if (temporalRe.test(dim.value)) return true
  return props.dataset.rows.some((r) => temporalValueRe.test(String(r[dim.value!] ?? '')))
})
chartType.value = temporal.value ? 'line' : 'bar'

function formatCell(row: any, _col: any, value: any): string {
  return value === null || value === undefined ? '' : String(value)
}

function buildOption(): echarts.EChartsCoreOption {
  const css = getComputedStyle(document.documentElement)
  const textColor = css.getPropertyValue('--el-text-color-regular').trim() || '#606266'
  const lineColor = css.getPropertyValue('--el-border-color-light').trim() || '#dcdfe6'
  const categories = props.dataset.rows.map((r) => String(r[dim.value!] ?? ''))
  const grid = { left: 8, right: 16, top: 32, bottom: 8, containLabel: true }
  const legend = {
    top: 0,
    type: 'scroll' as const,
    itemWidth: 14,
    itemHeight: 8,
    textStyle: { color: textColor, fontSize: 11 }
  }
  const used = measures.value.slice(0, 3)
  if (chartType.value === 'pie') {
    const m = used[0]
    return {
      color: PALETTE,
      tooltip: { trigger: 'item' },
      legend,
      series: [
        {
          type: 'pie',
          radius: ['30%', '62%'],
          center: ['50%', '56%'],
          data: props.dataset.rows.map((r) => ({ name: String(r[dim.value!] ?? ''), value: numOf(r[m]) }))
        }
      ]
    }
  }
  const series = used.map((m) => ({
    name: m,
    type: chartType.value === 'line' ? ('line' as const) : ('bar' as const),
    smooth: true,
    data: props.dataset.rows.map((r) => numOf(r[m]))
  }))
  if (chartType.value === 'barH') {
    return {
      color: PALETTE,
      tooltip: { trigger: 'axis' },
      legend,
      grid,
      xAxis: { type: 'value', axisLabel: { color: textColor }, splitLine: { lineStyle: { color: lineColor } } },
      yAxis: {
        type: 'category',
        data: categories,
        inverse: true,
        axisLabel: { color: textColor, width: 90, overflow: 'truncate' },
        axisLine: { lineStyle: { color: lineColor } }
      },
      series
    }
  }
  return {
    color: PALETTE,
    tooltip: { trigger: 'axis' },
    legend,
    grid,
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { color: textColor, width: 90, overflow: 'truncate' },
      axisLine: { lineStyle: { color: lineColor } }
    },
    yAxis: { type: 'value', axisLabel: { color: textColor }, splitLine: { lineStyle: { color: lineColor } } },
    series
  }
}

function renderChart() {
  nextTick(() => {
    if (!chartEl.value) return
    // v-if 切换视图会重建容器 DOM：旧实例还挂在已移除的节点上，必须销毁重建，否则 setOption 画到游离节点（图表空白）
    if (chart && chart.getDom() !== chartEl.value) {
      chart.dispose()
      chart = null
    }
    chart = chart || echarts.init(chartEl.value)
    chart.setOption(buildOption(), true)
    chart.resize()
  })
}

function resizeChart() {
  chart?.resize()
}

watch([view, chartType], () => {
  if (view.value === 'chart') renderChart()
})

onMounted(() => window.addEventListener('resize', resizeChart))
onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeChart)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.dataset-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}
.dataset-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.dataset-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.dataset-ops {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.dataset-sql {
  border: none;
  font-size: 12px;
}
.dataset-sql :deep(.el-collapse-item__header) {
  color: var(--el-text-color-secondary);
  height: 26px;
  line-height: 26px;
}
.dataset-sql :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}
.dataset-sql :deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}
.dataset-sql-code {
  display: block;
  padding: 6px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.dataset-chart {
  width: 100%;
  height: 260px;
}
</style>
