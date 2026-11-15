<template>
  <div v-if="visible" class="up-mask" @click.self="$emit('close')">
    <aside class="up">
      <div class="up-head">
        <b>{{ title }}</b>
        <button class="x" @click="$emit('close')">×</button>
      </div>

      <div class="up-body">
        <div class="up-id">
          <img v-if="faceUrl" class="up-face" :src="assetUrl(faceUrl)" alt="" />
          <span v-else class="up-face char" :class="{ bot: isBot }">{{ isBot ? '🤖' : name.charAt(0) }}</span>
          <b class="up-name">{{ name }}</b>
          <span v-if="isBot" class="up-tag bot">机器人</span>
          <span v-else-if="mode === 'self'" class="up-tag">我</span>
        </div>

        <!-- 本人模式：更换/恢复默认头像 -->
        <div v-if="mode === 'self'" class="up-avatar-ops">
          <button class="sm" :disabled="avatarBusy" @click="pickFile">{{ avatarBusy ? '上传中…' : '更换头像' }}</button>
          <button class="sm" :disabled="avatarBusy || !hasCustomAvatar" @click="doReset">恢复默认</button>
          <input ref="fileEl" type="file" accept=".png,.jpg,.jpeg,.gif,.webp" style="display: none" @change="doUpload" />
        </div>
        <p v-if="mode === 'self'" class="up-hint">头像仅对聊天客户端生效（独立于系统头像）；未自设时展示系统头像，都没有则显示首字母。图片 ≤2MB，支持 png/jpg/gif/webp</p>

        <div v-if="loading" class="up-empty">加载中…</div>
        <div v-else-if="errorMsg" class="up-err">{{ errorMsg }}</div>
        <template v-else-if="mode !== 'bot'">
          <div class="up-sec">
            <div class="up-row"><span class="k">账号</span><span class="v">{{ profile?.username || '—' }}</span></div>
            <div class="up-row"><span class="k">部门</span><span class="v">{{ profile?.deptPath || '—' }}</span></div>
            <div class="up-row"><span class="k">手机</span><span class="v">{{ profile?.mobile || '—' }}</span></div>
            <div class="up-row"><span class="k">邮箱</span><span class="v">{{ profile?.email || '—' }}</span></div>
          </div>
        </template>
        <div v-else class="up-sec">
          <div class="up-row"><span class="k">机器人 ID</span><span class="v">{{ bot?.botUserId || '—' }}</span></div>
          <div class="up-row"><span class="k">智能体编码</span><span class="v">{{ bot?.agentCode || '—' }}</span></div>
        </div>
      </div>

      <div v-if="mode !== 'self'" class="up-foot">
        <button class="primary" @click="$emit('chat')">发消息</button>
      </div>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import * as mse from '../mse'
import { assetUrl } from '../chat/util'

/**
 * 资料卡抽屉（对齐 GroupDrawer 风格），三种模式：
 *  - user：指定用户，经 /im/client/user/{id}/profile 拉基座资料（部门路径/手机/邮箱）；
 *  - bot：机器人（数据来自通讯录 bots 集，无独立端点）；
 *  - self：本人——同 user 拉资料，另提供 IM 自设头像上传/恢复（生效地址经 avatar-changed 上抛）。
 */
const props = defineProps<{
  visible: boolean
  mode: 'user' | 'bot' | 'self'
  /** user/self 模式的用户 id（self 即我） */
  userId?: string
  /** bot 模式的机器人资料（通讯录集） */
  bot?: mse.ContactBot | null
  /** 本人当前生效头像（self 模式回显） */
  meFaceUrl?: string
}>()

const emit = defineEmits<{
  close: []
  /** 发消息（user/bot 模式）：带目标 id（bot 为 bot_ 前缀串） */
  chat: [targetId: string]
  /** 自设头像变更（self 模式）：带生效头像地址（空串=回落首字母） */
  'avatar-changed': [faceURL: string]
}>()

const profile = ref<mse.UserProfile | null>(null)
const loading = ref(false)
const errorMsg = ref('')
const avatarBusy = ref(false)
const fileEl = ref<HTMLInputElement>()

const isBot = computed(() => props.mode === 'bot')
const name = computed(() => {
  if (props.mode === 'bot') return props.bot?.nickname || props.bot?.botUserId || '机器人'
  return profile.value?.nickname || ''
})
const faceUrl = computed(() => {
  if (props.mode === 'bot') return props.bot?.faceUrl || ''
  if (props.mode === 'self') return props.meFaceUrl || ''
  return profile.value?.avatar || ''
})
const title = computed(() => (props.mode === 'self' ? '我的资料' : '资料卡'))
const hasCustomAvatar = computed(() => props.mode === 'self' && !!props.meFaceUrl)

watch(() => props.visible, (v) => {
  if (!v) return
  profile.value = null
  errorMsg.value = ''
  if (props.mode !== 'bot') loadProfile()
})

async function loadProfile() {
  const id = props.userId
  if (!id) return
  loading.value = true
  try {
    profile.value = await mse.getUserProfile(id)
  } catch (e: any) {
    errorMsg.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

function pickFile() {
  fileEl.value?.click()
}

async function doUpload(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  // 与后端 WorkbenchIconRules 同口径先本地拦截，不发请求
  if (file.size > 2 * 1024 * 1024) {
    errorMsg.value = '头像图片不能超过 2MB，请压缩后再选'
    return
  }
  avatarBusy.value = true
  try {
    const url = await mse.uploadAvatar(file)
    emit('avatar-changed', url || '')
  } catch (e: any) {
    errorMsg.value = e?.message || String(e)
  } finally {
    avatarBusy.value = false
  }
}

async function doReset() {
  avatarBusy.value = true
  try {
    const url = await mse.resetAvatar()
    emit('avatar-changed', url || '')
  } catch (e: any) {
    errorMsg.value = e?.message || String(e)
  } finally {
    avatarBusy.value = false
  }
}
</script>

<style scoped>
.up-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .25); z-index: 50; display: flex; justify-content: flex-end; }
.up { width: 320px; max-width: 86vw; background: var(--im-panel-bg); height: 100vh; display: flex; flex-direction: column; box-shadow: -6px 0 24px var(--im-shadow); }
.up-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--im-border); }
.up-head b { font-size: 15px; }
.up-head .x { border: none; background: transparent; font-size: 20px; color: var(--im-text-2); padding: 0 4px; cursor: pointer; }
.up-body { flex: 1; overflow: auto; min-height: 0; }

.up-id { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 22px 16px 12px; }
.up-face { width: 72px; height: 72px; border-radius: 14px; object-fit: cover; flex: none; }
.up-face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 30px; }
.up-face.char.bot { background: #00b04f; font-size: 34px; }
.up-name { font-size: 16px; max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.up-tag { font-size: 10px; border-radius: 8px; padding: 0 6px; line-height: 16px; color: var(--im-primary); border: 1px solid var(--im-primary); }
.up-id .up-tag.bot { color: #00b04f; border-color: #00b04f; }

.up-avatar-ops { display: flex; gap: 8px; justify-content: center; padding: 0 16px 6px; }
.up-avatar-ops .sm { padding: 4px 12px; font-size: 12px; }
.up-hint { margin: 0; padding: 4px 16px 10px; font-size: 11px; color: var(--im-text-2); text-align: center; }

.up-sec { padding: 6px 12px; border-top: 1px solid var(--im-border); }
.up-row { display: flex; align-items: baseline; gap: 8px; padding: 9px 6px; font-size: 13px; }
.up-row .k { color: var(--im-text-2); flex: none; width: 64px; }
.up-row .v { flex: 1; min-width: 0; word-break: break-all; text-align: right; }
.up-empty { color: var(--im-text-2); font-size: 12px; text-align: center; padding: 24px 0; }
.up-err { margin: 0; padding: 8px 16px; font-size: 12px; color: #e34d59; word-break: break-all; }

.up-foot { display: flex; gap: 8px; padding: 10px 16px; border-top: 1px solid var(--im-border); flex: none; }
.up-foot button { flex: 1; }
</style>
