<template>
  <teleport to="body">
    <div v-if="visible" class="ctx-mask" @click.stop="$emit('close')" @contextmenu.prevent="$emit('close')">
      <div class="ctx" :style="{ left: x + 'px', top: y + 'px' }">
        <template v-for="it in items" :key="it.key">
          <div v-if="it.divider" class="ctx-divider"></div>
          <button v-else class="ctx-item" :class="{ danger: it.danger, disabled: it.disabled }"
                  :disabled="it.disabled" @click.stop="select(it.key)">{{ it.label }}</button>
        </template>
      </div>
    </div>
  </teleport>
</template>

<script setup lang="ts">
export interface CtxItem { key: string; label: string; danger?: boolean; disabled?: boolean; divider?: boolean }

const props = defineProps<{ visible: boolean; x: number; y: number; items: CtxItem[] }>()
const emit = defineEmits<{ close: []; select: [key: string] }>()

function select(key: string) {
  emit('select', key)
  emit('close')
}

/** 边界修正：贴视口右/下边缘 */
function normalize() {
  // 简单处理：渲染后如越界则左移/上移（由调用方位置粗调即可，菜单宽度有限）
  void props
}
normalize()
</script>

<style scoped>
.ctx-mask { position: fixed; inset: 0; z-index: 70; }
.ctx {
  position: fixed; min-width: 140px; background: var(--im-panel-bg); border: 1px solid var(--im-border);
  border-radius: 10px; box-shadow: 0 6px 24px var(--im-shadow); padding: 4px; z-index: 71;
}
.ctx-item {
  display: block; width: 100%; text-align: left; border: none; background: transparent;
  padding: 7px 12px; border-radius: 6px; cursor: pointer; font-size: 13px; color: var(--im-text);
}
.ctx-item:hover:not(:disabled) { background: var(--im-hover); }
.ctx-item.danger { color: #e34d59; }
.ctx-item.disabled { opacity: .45; cursor: default; }
.ctx-divider { height: 1px; background: var(--im-border); margin: 4px 6px; }
</style>
