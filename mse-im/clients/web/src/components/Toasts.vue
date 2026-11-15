<template>
  <div class="toasts">
    <transition-group name="toast">
      <div v-for="t in toasts" :key="t.id" class="toast" :class="t.kind">{{ t.text }}</div>
    </transition-group>
  </div>
</template>

<script setup lang="ts">
import { toasts } from '../chat/toast'
</script>

<style scoped>
.toasts { position: fixed; top: 18px; left: 50%; transform: translateX(-50%); z-index: 90; display: flex; flex-direction: column; align-items: center; gap: 8px; pointer-events: none; }
.toast { max-width: 70vw; padding: 9px 16px; border-radius: 10px; font-size: 13px; background: var(--im-panel-bg); color: var(--im-text); box-shadow: 0 6px 24px var(--im-shadow); border: 1px solid var(--im-border); word-break: break-all; }
.toast.success { border-color: rgba(0, 176, 79, .45); color: #00b04f; }
.toast.error { border-color: rgba(232, 74, 74, .5); color: #e84a4a; }
.toast-enter-active, .toast-leave-active { transition: all .22s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(-8px); }
</style>
