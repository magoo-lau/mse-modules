<template>
  <div class="msg-viewer">
    <div v-for="(m, i) in messages" :key="i" class="msg-item">
      <div class="msg-head">
        <el-tag size="small" effect="plain" :type="roleTag(m.role)">{{ roleLabel(m.role) }}</el-tag>
        <span v-if="m.toolCalls?.length" class="msg-meta">请求工具 ×{{ m.toolCalls.length }}</span>
        <span v-if="m.responses?.length" class="msg-meta">工具回传 ×{{ m.responses.length }}</span>
      </div>
      <pre v-if="m.content" class="msg-body">{{ m.content }}</pre>
      <div v-for="(c, j) in m.toolCalls || []" :key="'c' + j" class="msg-sub">
        <span class="msg-sub-label">{{ c.name }}</span>
        <pre class="msg-body">{{ pretty(c.arguments) }}</pre>
      </div>
      <div v-for="(r, j) in m.responses || []" :key="'r' + j" class="msg-sub">
        <span class="msg-sub-label">{{ r.name }}</span>
        <pre class="msg-body">{{ pretty(r.content) }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ModelMessageSnapshot } from '../api/ai'

/**
 * 模型输入消息查看器（调试台轮次轨迹）：展示某轮模型实际收到的完整消息序列
 * （系统提示词 / 用户消息 / 助手中间输出 / 工具调用与回传），只读、限高滚动。
 */
defineProps<{ messages: ModelMessageSnapshot[] }>()

const ROLE_LABELS: Record<string, string> = {
  system: '系统',
  user: '用户',
  assistant: '助手',
  tool: '工具',
  function: '函数'
}

function roleLabel(role: string): string {
  return ROLE_LABELS[role] || role
}

function roleTag(role: string): 'warning' | 'primary' | 'success' | 'info' {
  return role === 'system' ? 'warning' : role === 'user' ? 'primary' : role === 'assistant' ? 'success' : 'info'
}

function pretty(text?: string): string {
  if (!text) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}
</script>

<style scoped>
.msg-viewer {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.msg-item {
  padding: 6px 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
}
.msg-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.msg-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.msg-body {
  margin: 4px 0 0;
  padding: 6px;
  border-radius: 4px;
  background: var(--el-fill-color-dark);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}
.msg-sub {
  margin-top: 4px;
}
.msg-sub-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
}
</style>
