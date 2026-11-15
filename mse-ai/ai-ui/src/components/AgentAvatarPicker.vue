<template>
  <div class="avatar-picker">
    <agent-avatar :avatar="modelValue" :size="36" />
    <el-popover placement="bottom-start" :width="304" trigger="click">
      <template #reference>
        <el-button size="small">选图标</el-button>
      </template>
      <div v-for="g in AGENT_AVATAR_GROUPS" :key="g.label" class="emoji-group">
        <div class="emoji-group-label">{{ g.label }}</div>
        <div class="emoji-grid">
          <button
            v-for="e in g.emojis"
            :key="e"
            type="button"
            class="emoji-cell"
            :class="{ active: e === modelValue }"
            @click="pick(e)"
          >
            {{ e }}
          </button>
        </div>
      </div>
    </el-popover>
    <el-upload
      :show-file-list="false"
      :auto-upload="false"
      accept=".png,.jpg,.jpeg,.gif,.webp"
      :on-change="onFileChange"
    >
      <el-button size="small" :loading="uploading">上传</el-button>
    </el-upload>
    <el-button v-if="modelValue" size="small" text type="danger" @click="pick('')">清空</el-button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, type UploadFile } from 'element-plus'
import AgentAvatar from './AgentAvatar.vue'
import { AGENT_AVATAR_GROUPS } from '../constants/agentAvatars'
import { agentApi } from '../api/ai'

/**
 * 智能体头像选择器：系统内置图标（emoji 网格）+ 本地上传（≤2MB，站内地址落库）。
 * modelValue 兼容三种值：emoji 字面量 / 站内路径 / 绝对 URL（历史手输值原样保留）。
 */
defineProps<{ modelValue?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const uploading = ref(false)

function pick(emoji: string) {
  emit('update:modelValue', emoji)
}

async function onFileChange(file: UploadFile) {
  const raw = file.raw
  if (!raw) return
  if (raw.size > 2 * 1024 * 1024) {
    ElMessage.warning('头像不能超过 2MB')
    return
  }
  uploading.value = true
  try {
    const url = (await agentApi.uploadAgentAvatar(raw)) as unknown as string
    emit('update:modelValue', url)
  } finally {
    uploading.value = false
  }
}
</script>

<style scoped>
.avatar-picker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.emoji-group + .emoji-group {
  margin-top: 8px;
}
.emoji-group-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.emoji-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 2px;
}
.emoji-cell {
  border: none;
  background: transparent;
  font-size: 20px;
  line-height: 1;
  padding: 4px;
  border-radius: 6px;
  cursor: pointer;
}
.emoji-cell:hover {
  background: var(--el-fill-color);
}
.emoji-cell.active {
  background: var(--el-color-primary-light-8);
}
</style>
