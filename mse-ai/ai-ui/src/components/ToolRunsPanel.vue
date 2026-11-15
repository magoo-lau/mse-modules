<template>
  <div class="runs-panel">
    <div v-for="(s, i) in steps" :key="s.seq ?? i" class="run-item">
      <div class="run-head">
        <span class="run-icon" :class="s.status">{{ icon(s.status) }}</span>
        <span class="run-seq">#{{ s.seq }}</span>
        <span class="run-tool">{{ s.tool }}</span>
        <el-tag v-if="s.turn" size="small" effect="plain" :disable-transitions="true">第 {{ s.turn }} 轮</el-tag>
        <el-tag size="small" effect="plain" :type="s.status === 'success' ? 'success' : s.status === 'fail' ? 'danger' : 'warning'" :disable-transitions="true">
          {{ s.status === 'success' ? '成功' : s.status === 'fail' ? '失败' : s.status === 'limit' ? '超限拦截' : '执行中' }}
        </el-tag>
        <span v-if="s.elapsedMs != null" class="run-ms">{{ s.elapsedMs }}ms</span>
        <span v-if="repeatCount(s) > 1" class="run-repeat">相同参数第 {{ repeatCount(s) }} 次</span>
      </div>
      <div v-if="s.arguments" class="run-block">
        <div class="run-label">入参</div>
        <pre class="run-body">{{ pretty(s.arguments) }}</pre>
      </div>
      <div v-if="s.result" class="run-block">
        <div class="run-label">返回</div>
        <pre class="run-body" :class="{ empty: isEmptyResult(s.result) || isDirective(s.result) }">{{ pretty(s.result) }}</pre>
        <div v-if="isDirective(s.result)" class="run-empty-tip">🛑 返回已注入终止指令（空结果即停）——模型将直接如实作答，不再重试</div>
        <div v-else-if="isEmptyResult(s.result)" class="run-empty-tip">⚠️ 成功但无数据（数据数组全空）——后端已终止工具循环，空结果不重试</div>
      </div>
      <div v-if="s.error" class="run-error">{{ s.error }}</div>
      <DatasetCard v-if="s.dataset" :dataset="s.dataset" />
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ToolCallStep } from '../api/ai'
import DatasetCard from './DatasetCard.vue'

/**
 * 调试台「工具运行记录」整合面板：跨轮次按时间序平铺全部工具执行，
 * 每条含序号/轮次/状态/耗时（相同参数重复计数）+ 完整入参与返回内容 + 数据集卡片内联；
 * 空数据返回高亮提示（配合后端空结果终止引导）。
 */
const props = defineProps<{ steps: ToolCallStep[] }>()

/** 同（工具+入参）此前出现次数（含本次）：>1 即重复调用 */
function repeatCount(step: ToolCallStep): number {
  return props.steps.filter((s) => s.tool === step.tool && (s.arguments || '') === (step.arguments || '')
    && (s.seq ?? 0) <= (step.seq ?? 0)).length
}

/** 成功但无数据（数据数组全空）——与后端 isEmptyDataResult 判定同口径的展示提示 */
function isEmptyResult(result: string | null | undefined): boolean {
  if (!result) return false
  try {
    const json = JSON.parse(result)
    if (!json || typeof json !== 'object' || Array.isArray(json)) return false
    const fields = ['rows', 'tables', 'records', 'data', 'list', 'results', 'items']
    let has = false
    for (const f of fields) {
      if (Array.isArray(json[f])) {
        has = true
        if (json[f].length > 0) return false
      }
    }
    return has
  } catch {
    return false
  }
}

/** 返回已注入终止指令（回调层空结果包装后的形态，非 JSON） */
function isDirective(result: string | null | undefined): boolean {
  return !!result && result.includes('【系统指令】') && result.includes('未查询到任何数据')
}

function icon(status: string): string {
  return status === 'success' ? '✓' : status === 'fail' ? '✕' : status === 'limit' ? '⛔' : '◌'
}

function pretty(text?: string | null): string {
  if (!text) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}
</script>

<style scoped>
.runs-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.run-item {
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-bg-color);
}
.run-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.run-icon.success {
  color: var(--el-color-success);
}
.run-icon.fail {
  color: var(--el-color-danger);
}
.run-icon.limit {
  color: var(--el-color-warning);
}
.run-icon.running {
  color: var(--el-text-color-secondary);
}
.run-seq {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: monospace;
}
.run-tool {
  font-size: 13px;
  font-weight: 600;
}
.run-ms {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.run-repeat {
  font-size: 12px;
  color: var(--el-color-warning);
}
.run-block {
  margin-top: 6px;
}
.run-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 2px;
}
.run-body {
  margin: 0;
  padding: 6px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 220px;
  overflow: auto;
}
.run-body.empty {
  border-left: 2px solid var(--el-color-warning);
}
.run-empty-tip {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-warning);
}
.run-error {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-color-danger);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
