<template>
  <el-collapse class="turn-inputs-panel">
    <el-collapse-item name="inputs">
      <template #title>
        <span class="panel-title">📥 模型输入（{{ rounds.length }} 轮 · 实际发送的完整 prompt）</span>
      </template>
      <el-collapse class="round-list">
        <el-collapse-item v-for="r in rounds" :key="r.index" :name="String(r.index)">
          <template #title>
            <span class="round-title">第 {{ r.index }} 轮<template v-if="r.elapsedMs != null"> · {{ r.elapsedMs }}ms</template>（{{ r.messages.length }} 条消息）</span>
          </template>
          <ModelMessagesViewer :messages="r.messages" />
        </el-collapse-item>
      </el-collapse>
    </el-collapse-item>
  </el-collapse>
</template>

<script setup lang="ts">
import type { ChatRoundInput } from '../api/ai'
import ModelMessagesViewer from './ModelMessagesViewer.vue'

/**
 * 对话工作台「模型输入」面板（ai:chat:trace 权限者可见，与工具轨迹面板同权限）：
 * 按模型轮次展开该轮实际收到的完整 prompt 消息序列（系统提示词 / RAG 注入 /
 * 记忆历史 / 工具调用与回传）。数据源：SSE turn 事件实时构建，或消息 ext.turns 历史回放。
 */
defineProps<{ rounds: ChatRoundInput[] }>()
</script>

<style scoped>
.turn-inputs-panel {
  border: none;
  font-size: 12px;
}
.turn-inputs-panel :deep(.el-collapse-item__header) {
  color: var(--el-text-color-secondary);
  height: 28px;
  line-height: 28px;
}
.turn-inputs-panel :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}
.turn-inputs-panel :deep(.el-collapse-item__content) {
  padding-bottom: 4px;
}
.panel-title {
  font-size: 12px;
}
.round-list {
  border: none;
}
.round-list :deep(.el-collapse-item__header) {
  height: 26px;
  line-height: 26px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 0 8px;
  margin-bottom: 4px;
}
.round-list :deep(.el-collapse-item__wrap) {
  background: transparent;
  border: none;
}
.round-list :deep(.el-collapse-item__content) {
  padding-bottom: 8px;
}
.round-title {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
