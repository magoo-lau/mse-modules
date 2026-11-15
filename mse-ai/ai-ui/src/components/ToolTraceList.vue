<template>
  <el-collapse class="tool-trace-panel">
    <el-collapse-item name="tools">
      <template #title>
        <span class="trace-title">
          🔧 工具调用（{{ steps.length }} 次<template v-if="totalMs != null"> · {{ totalMs }}ms</template>）
        </span>
      </template>
      <!-- 多轮工具调用按轮次分组（轮次号由后端随事件标注；旧数据无轮次号保持平铺） -->
      <template v-for="group in turnGroups || [{ turn: 0, steps }]" :key="group.turn">
        <div v-if="turnGroups" class="turn-label">第 {{ group.turn }} 轮</div>
        <div v-for="(s, i) in group.steps" :key="s.seq ?? i" class="tool-step">
          <div class="step-head">
            <span class="step-icon" :class="s.status">{{ icon(s.status) }}</span>
            <span class="step-tool">{{ s.tool }}</span>
            <el-tag size="small" effect="plain">{{ s.status === 'success' ? '成功' : s.status === 'fail' ? '失败' : s.status === 'limit' ? '超限拦截' : '执行中' }}</el-tag>
            <span v-if="s.elapsedMs != null" class="step-ms">{{ s.elapsedMs }}ms</span>
          </div>
          <div v-if="s.arguments" class="step-args">
            <div class="args-label">入参</div>
            <pre class="args-body">{{ pretty(s.arguments) }}</pre>
          </div>
          <div v-if="s.result" class="step-args">
            <div class="args-label">返回</div>
            <pre class="args-body">{{ pretty(s.result) }}</pre>
          </div>
          <div v-if="s.dataset" class="step-dataset">
            返回数据集：{{ s.dataset.rowCount }} 行<template v-if="s.dataset.hasMore">+（已截断）</template>
            <template v-if="s.dataset.sql">，SQL 见下方数据卡片</template>
          </div>
          <div v-if="s.error" class="step-error">{{ s.error }}</div>
        </div>
      </template>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ToolCallStep } from '../api/ai'

/**
 * 工具调用轨迹（对话工作台消息内联 / 智能体调试台共用）：
 * 每步 = 工具 + 状态 + 耗时 + 入参 JSON + 返回内容 JSON + 失败原因/超限拦截说明。
 * 数据源：SSE tool 事件实时聚合，或消息 ext.toolCalls 历史回放。
 * 步骤带轮次号（turn）且多于一轮时按「第 N 轮」分组展示。
 */
const props = defineProps<{ steps: ToolCallStep[] }>()

const totalMs = computed(() => {
  const list = props.steps.filter((s) => s.elapsedMs != null)
  return list.length ? list.reduce((sum, s) => sum + (s.elapsedMs || 0), 0) : null
})

/** 按轮次分组（仅步骤标注了轮次且存在多轮时；单轮/旧数据返回 null 平铺） */
const turnGroups = computed<{ turn: number; steps: ToolCallStep[] }[] | null>(() => {
  const turns = props.steps.map((s) => s.turn || 0)
  if (!turns.length || !turns.some((t) => t > 1)) return null
  const max = Math.max(...turns)
  const groups: { turn: number; steps: ToolCallStep[] }[] = []
  for (let t = 1; t <= max; t++) {
    const list = props.steps.filter((s) => (s.turn || 1) === t)
    if (list.length) groups.push({ turn: t, steps: list })
  }
  return groups.length > 1 ? groups : null
})

function icon(status: string): string {
  return status === 'success' ? '✓' : status === 'fail' ? '✕' : status === 'limit' ? '⛔' : '◌'
}

function pretty(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}
</script>

<style scoped>
.tool-trace-panel {
  border: none;
  font-size: 12px;
}
.tool-trace-panel :deep(.el-collapse-item__header) {
  color: var(--el-text-color-secondary);
  height: 28px;
  line-height: 28px;
}
.tool-trace-panel :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}
.tool-trace-panel :deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}
.trace-title {
  font-size: 12px;
}
.turn-label {
  margin: 6px 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
}
.tool-step {
  padding: 4px 8px;
  margin-bottom: 4px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.step-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.step-icon.success {
  color: var(--el-color-success);
}
.step-icon.fail {
  color: var(--el-color-danger);
}
.step-icon.limit {
  color: var(--el-color-warning);
}
.step-icon.running {
  color: var(--el-text-color-secondary);
}
.step-tool {
  font-family: inherit;
}
.step-ms {
  color: var(--el-text-color-secondary);
}
.step-args {
  margin-top: 4px;
}
.args-label {
  color: var(--el-text-color-secondary);
  margin-bottom: 2px;
}
.args-body {
  margin: 0;
  padding: 6px;
  border-radius: 4px;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-regular);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow: auto;
}
.step-dataset {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
}
.step-error {
  margin-top: 4px;
  color: var(--el-color-danger);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
