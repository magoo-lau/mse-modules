<template>
  <div class="as" @click.stop="open">
    <span class="as-icon">
      <img v-if="isImgIcon(card.icon)" :src="assetUrl(card.icon)" alt="" />
      <component v-else-if="epIconComp(card.icon)" :is="epIconComp(card.icon)" />
      <span v-else>{{ card.icon || '🧩' }}</span>
    </span>
    <span class="as-main">
      <span class="as-title">{{ card.title || '页面分享' }}</span>
      <span v-if="card.desc" class="as-desc">{{ card.desc }}</span>
      <span class="as-url">{{ card.url }}</span>
      <span class="as-open">{{ opening ? '免登签发中…' : '点击打开页面 →' }}</span>
    </span>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { AppShareData } from '../custom'
import { assetUrl, epIconComp } from '../util'
import * as mse from '../../mse'

const props = defineProps<{ card: AppShareData }>()
const opening = ref(false)

/** 卡片图标形态：http(s)/站内相对路径为图片，Element Plus 图标名按组件渲染，其余按 emoji 文本（空回退 🧩，兼容旧消息） */
function isImgIcon(icon?: string) {
  const u = icon || ''
  return /^https?:\/\//i.test(u) || u.startsWith('/')
}

/** webBase 只拉一次（租户工作台跳转基址）；拉不到时视作不同站点直开 */
let webBasePromise: Promise<string> | null = null
function webBaseUrl(): Promise<string> {
  webBasePromise ||= mse.workbench().then(v => v.webBaseUrl).catch(() => '')
  return webBasePromise
}

/**
 * 点击打开：与工作台「新窗口打开」同款一次性 code 免登（bare=1 无壳直达）。
 * 仅当卡片 origin 与租户 webBase 一致才签发——sso code 绑定站点 Origin，
 * 对不可信地址签发等于把签发者会话递过去；其余情况按普通外链直开（不附 code）。
 */
async function open() {
  if (opening.value) return
  opening.value = true
  const sep = props.card.url.includes('?') ? '&' : '?'
  const target = `${props.card.url}${sep}bare=1`
  try {
    const sameOrigin = sameOriginOf(props.card.url, await webBaseUrl())
    window.open(sameOrigin ? await mse.ssoJump(target).catch(() => target) : target, '_blank')
  } finally {
    opening.value = false
  }
}

function sameOriginOf(a: string, b: string): boolean {
  try { return !!b && new URL(a).origin === new URL(b).origin } catch { return false }
}
</script>

<style scoped>
.as { display: flex; gap: 10px; max-width: 300px; cursor: pointer; }
.as-icon { width: 56px; height: 56px; border-radius: 8px; flex: none; background: var(--im-hover); overflow: hidden; display: flex; align-items: center; justify-content: center; font-size: 26px; }
.as-icon img { width: 100%; height: 100%; object-fit: cover; }
.as-icon svg { width: 28px; height: 28px; }
.as-main { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.as-title { font-weight: 600; font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.as-desc { font-size: 12px; color: var(--im-text-2); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.as-url { font-size: 11px; color: var(--im-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.as-open { font-size: 11px; color: var(--im-primary); }
.as:hover .as-title { text-decoration: underline; }
</style>
