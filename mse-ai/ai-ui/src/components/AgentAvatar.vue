<template>
  <span class="agent-avatar" :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.62)}px` }">
    <img v-if="isImage" :src="resolveAssetSrc(avatar)" :alt="name" loading="lazy" />
    <template v-else>{{ avatar || '🤖' }}</template>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { resolveAssetSrc } from '../utils/asset'

/**
 * 智能体头像展示：URL（http(s)/站内路径）→ 图片，否则按 emoji 文本渲染（缺省 🤖）。
 */
const props = withDefaults(defineProps<{ avatar?: string; name?: string; size?: number }>(), {
  avatar: '',
  name: '',
  size: 22
})

const isImage = computed(() => !!props.avatar && /^(https?:\/\/|\/)/.test(props.avatar))
</script>

<style scoped>
.agent-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  vertical-align: middle;
  border-radius: 6px;
  overflow: hidden;
  line-height: 1;
  background: var(--el-fill-color-light);
}
.agent-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
</style>
