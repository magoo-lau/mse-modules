import { reactive } from 'vue'

export interface Toast { id: number; text: string; kind: 'info' | 'success' | 'error' }

let seq = 0

/** 全局提示队列：主界面内操作反馈的统一出口（连接状态仍走 setStatus 进 tooltip/登录页） */
export const toasts = reactive<Toast[]>([])

/** 轻提示：默认 info 2.6s 自动消失，error 停留 6s 便于读清原因 */
export function toast(text: string, kind: Toast['kind'] = 'info') {
  const id = ++seq
  toasts.push({ id, text, kind })
  setTimeout(() => {
    const i = toasts.findIndex(t => t.id === id)
    if (i >= 0) toasts.splice(i, 1)
  }, kind === 'error' ? 6000 : 2600)
}
