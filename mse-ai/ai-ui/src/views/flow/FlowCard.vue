<template>
  <div class="flow-card" :class="{ selected: props.selected, failed: props.data?.lastStatus === 'failed' }"
    :style="{ borderColor: props.selected ? meta.color : undefined }">
    <Handle type="target" :position="Position.Left" />
    <div class="card-head" :style="{ background: meta.color + '1a' }">
      <span class="icon">{{ meta.icon }}</span>
      <span class="title">{{ props.data?.dsl?.title || meta.label }}</span>
      <span v-if="props.data?.lastStatus === 'running'" class="spin">◌</span>
      <span v-else-if="props.data?.lastStatus === 'success'" class="ok">✓</span>
      <span v-else-if="props.data?.lastStatus === 'failed'" class="bad">✕</span>
    </div>
    <div class="card-body">{{ brief }}</div>
    <!-- 输出变量区：卡片上直接可见本节点产出什么；chip 点击复制引用，供下游粘贴 -->
    <div v-if="outputVars.length || outputHint" class="card-outs">
      <div class="outs-head">输出</div>
      <div v-for="v in outputVars" :key="v.name" class="out-row"
        :title="`点击复制 ${refText(v.name)}`" @click="copyRef(`${nodeId}.${v.name}`)">
        <span class="out-ref">{{ refText(v.name) }}</span>
        <span class="out-label">{{ v.label }}</span>
      </div>
      <div v-if="outputHint" class="out-hint">{{ outputHint }}</div>
    </div>
    <!-- 路由节点：多出口渲染为卡片内端口行（标签横排、卡片随分支数增高），普通节点单出口 -->
    <div v-if="handles.length" class="card-ports">
      <div v-for="h in handles" :key="h.id" class="port-row">
        <span class="port-label">{{ h.label }}</span>
        <Handle type="source" :id="h.id" :position="Position.Right" class="port-dot" />
      </div>
    </div>
    <Handle v-else type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Handle, Position } from '@vue-flow/core'
import { ASSIGN_OPS, metaOf, nodeOutputVars } from './nodeMeta'

const props = defineProps<{ id: string; data: any; selected?: boolean }>()

const meta = computed(() => metaOf(props.data?.dsl?.type || 'llm'))

const nodeId = computed(() => props.data?.dsl?.id || props.id)

/** 引用展示文本 {{nodeId.var}}（模板文本插值内不能直接写字面 {{，经函数输出） */
const refText = (name: string) => `{{${nodeId.value}.${name}}}`

/** 本节点输出变量（静态 + 动态配置项），code 节点返回键设计期未知走提示 */
const outputVars = computed(() => nodeOutputVars(props.data?.dsl))

const outputHint = computed(() =>
  props.data?.dsl?.type === 'code' ? '输出 = main(args) 返回对象的各键' : '')

function copyRef(ref: string) {
  const text = `{{${ref}}}`
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(text).catch(() => fallbackCopy(text))
  } else {
    fallbackCopy(text)
  }
  ElMessage.success({ message: `已复制 ${text}`, duration: 1500 })
}

/** http 非 localhost 环境 navigator.clipboard 为 undefined，隐藏 textarea 兜底 */
function fallbackCopy(text: string) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  document.execCommand('copy')
  document.body.removeChild(ta)
}

/** 路由节点的动态出口桩 */
const handles = computed(() => {
  const type = props.data?.dsl?.type
  const data = props.data?.dsl?.data || {}
  if (type === 'condition') {
    const items = (data.cases || []).map((_: any, i: number) => ({ id: `case${i}`, label: `条件${i + 1}` }))
    items.push({ id: 'default', label: '否则' })
    return items
  }
  if (type === 'classifier') {
    return (data.classes || []).map((c: any, i: number) => ({
      id: `case${i}`,
      label: c?.name || `分类${i + 1}`
    }))
  }
  return []
})

const brief = computed(() => {
  const data = props.data?.dsl?.data || {}
  const type = props.data?.dsl?.type
  if (type === 'llm') return data.prompt ? String(data.prompt).slice(0, 60) : '未配置提示词'
  if (type === 'knowledge') return `知识库：${data.kbIds || '未绑定'}`
  if (type === 'tool') return `工具：${data.toolCode || '未选择'}`
  if (type === 'dataset') return data.sql ? String(data.sql).slice(0, 60) : '未配置 SQL'
  if (type === 'condition') return `${(data.cases || []).length} 个用例`
  if (type === 'classifier') return `${(data.classes || []).length} 个分类`
  if (type === 'extractor') return `${(data.fields || []).length} 个字段`
  if (type === 'http') return `${data.method || 'GET'} ${data.url || ''}`.slice(0, 60)
  if (type === 'code') return 'JavaScript 沙箱'
  if (type === 'iteration') return `迭代：${data.items || '未配置'}`
  if (type === 'aggregator') return `聚合 ${(data.variables || []).length} 个变量`
  if (type === 'assign') {
    const items = data.assignments || []
    const marks = items
      .filter((a: any) => a.name && a.op && a.op !== 'set')
      .map((a: any) => `${a.name}（${ASSIGN_OPS.find((op) => op.value === a.op)?.label || a.op}）`)
    return marks.length ? `赋值 ${items.length} 项：${marks.join('、')}` : `赋值 ${items.length} 项`
  }
  if (type === 'start') return `${(data.variables || []).length} 个入参`
  return ''
})
</script>

<style scoped>
.flow-card {
  min-width: 180px;
  max-width: 240px;
  border: 1.5px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  font-size: 12px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}
.flow-card.selected {
  box-shadow: 0 0 0 2px var(--el-color-primary-light-5);
}
.card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 6px 6px 0 0;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.card-head .title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-body {
  padding: 5px 8px 7px;
  color: var(--el-text-color-secondary);
  min-height: 18px;
  word-break: break-all;
}
.spin,
.ok,
.bad {
  margin-left: 4px;
}
.ok {
  color: var(--el-color-success);
}
.bad {
  color: var(--el-color-danger);
}
.card-ports {
  border-top: 1px dashed var(--el-border-color-lighter);
  padding: 4px 8px 6px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.card-outs {
  border-top: 1px dashed var(--el-border-color-lighter);
  padding: 4px 8px 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.outs-head {
  font-size: 10px;
  color: var(--el-text-color-secondary);
  opacity: 0.75;
}
.out-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-height: 14px;
  cursor: copy;
}
.out-row:hover .out-ref {
  color: var(--el-color-primary);
  text-decoration: underline;
}
.out-ref {
  font-size: 11px;
  color: var(--el-color-primary-light-3);
  white-space: nowrap;
  flex: none;
}
.out-label {
  font-size: 10px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.out-hint {
  font-size: 10px;
  color: var(--el-text-color-secondary);
}
.port-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 14px;
}
.port-label {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 端口行内圆点：覆盖 vue-flow 默认的绝对定位，连线锚点按实际渲染位置计算 */
.port-row :deep(.vue-flow__handle) {
  position: static;
  transform: none;
  width: 7px;
  height: 7px;
  flex: none;
}
</style>
