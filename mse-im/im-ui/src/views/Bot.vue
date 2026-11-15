<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>IM 机器人</span>
          <el-button type="primary" size="small" @click="openCreate">新建</el-button>
        </div>
      </template>

      <el-table :data="bots" size="small" v-loading="loading">
        <el-table-column prop="botUserId" label="bot 账号" width="160" />
        <el-table-column prop="nickname" label="昵称" width="140" />
        <el-table-column label="绑定智能体" min-width="170">
          <template #default="{ row }">
            <span class="agent-cell">
              <img v-if="isImgSrc(agentAvatar(row.agentCode))" :src="resolveSrc(agentAvatar(row.agentCode))" class="agent-mini" />
              <span v-else class="agent-mini-emoji">{{ agentAvatar(row.agentCode) || '🤖' }}</span>
              <span>{{ agentLabel(row.agentCode) }}</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="triggerKeywords" label="触发关键词" min-width="140">
          <template #default="{ row }">{{ row.triggerKeywords || '（@ 即触发）' }}</template>
        </el-table-column>
        <el-table-column prop="groupIds" label="群白名单" min-width="120">
          <template #default="{ row }">{{ row.groupIds || '（所有群）' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm title="确认删除该机器人？" @confirm="remove(row)">
              <template #reference>
                <el-button link type="danger" size="small">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">
        机器人 = OpenIM 普通用户（bot_ 前缀）。单聊直发始终触发；群聊需 @ 机器人，且命中关键词（未配置时 @ 即触发）与群白名单。
        头像缺省使用绑定智能体的头像（emoji 自动转图片）；回复由绑定的 mse-ai 智能体生成后经管理端代发；需在「IM 设置」回填 webhook 回调地址后生效。
      </p>
    </el-card>

    <el-dialog v-model="dialog" :title="form.id ? '编辑机器人' : '新建机器人'" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="bot 账号">
          <el-input v-model="form.botUserId" :disabled="!!form.id" placeholder="xxx（留空自动生成）">
            <template #prepend>bot_</template>
          </el-input>
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" placeholder="机器人" />
        </el-form-item>
        <el-form-item label="智能体编码" required>
          <el-select v-model="form.agentCode" filterable placeholder="选择绑定的智能体（必填）" style="width: 100%"
                     @change="onAgentChange">
            <el-option v-for="o in agentOptions" :key="o.agentCode" :label="`${o.name}（${o.agentCode}）`"
                       :value="o.agentCode">
              <span class="agent-option">
                <img v-if="isImgSrc(o.avatar)" :src="resolveSrc(o.avatar)" class="opt-avatar" />
                <span v-else class="opt-emoji">{{ o.avatar || '🤖' }}</span>
                <span>{{ o.name }}（{{ o.agentCode }}）</span>
              </span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="触发关键词">
          <el-input v-model="form.triggerKeywords" placeholder="逗号分隔，留空 = 群内 @ 即触发" />
        </el-form-item>
        <el-form-item label="群白名单">
          <el-input v-model="form.groupIds" placeholder="逗号分隔 groupID，留空 = 所有群" />
        </el-form-item>
        <el-form-item label="头像">
          <div class="avatar-row">
            <span class="avatar-preview">
              <img v-if="isImgSrc(form.faceUrl)" :src="resolveSrc(form.faceUrl)" />
              <span v-else-if="form.faceUrl" class="avatar-emoji">{{ form.faceUrl }}</span>
              <span v-else class="avatar-emoji avatar-empty">🤖</span>
            </span>
            <el-popover placement="bottom-start" :width="288" trigger="click">
              <template #reference>
                <el-button size="small" :loading="avatarBusy">选图标</el-button>
              </template>
              <div class="emoji-grid">
                <button v-for="e in AVATAR_EMOJIS" :key="e" class="emoji-item" type="button"
                        :disabled="avatarBusy" @click="pickEmoji(e)">{{ e }}</button>
              </div>
            </el-popover>
            <el-upload :show-file-list="false" :auto-upload="false" accept=".png,.jpg,.jpeg,.gif,.webp"
                       :on-change="onAvatarFile">
              <el-button size="small" :loading="avatarBusy">上传</el-button>
            </el-upload>
            <el-button v-if="form.faceUrl" size="small" text type="danger" :disabled="avatarBusy"
                       @click="clearAvatar">清空</el-button>
          </div>
          <div class="field-hint">缺省使用绑定智能体的头像（emoji 自动转图片）；也可重新选图标或上传（≤2MB），保存后同步进 OpenIM 实例</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存（同步注册进实例）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deleteBot, listAgentOptions, listBots, saveBot, uploadBotAsset, type AgentOption, type ImBot } from '../api/im'

const bots = ref<ImBot[]>([])
const agentOptions = ref<AgentOption[]>([])
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
/** 头像转换/上传进行中（emoji 转 PNG、图片转传均为异步） */
const avatarBusy = ref(false)
/** faceUrl 是否由「带出智能体头像」自动写入：换绑智能体时跟随刷新；手动选图标/上传/清空后解除跟随 */
let faceUrlAuto = false

const form = ref<ImBot & { enabled?: boolean }>({})

/** 内置图标（emoji 精选集，与 mse-ai 智能体头像同源风格；独立工程各自持有清单） */
const AVATAR_EMOJIS = [
  '🤖', '🧠', '💡', '⚡', '✨', '🎯', '🔧', '⚙️',
  '📊', '📈', '📉', '🗂️', '📋', '📅', '🔍', '🧮',
  '📝', '✍️', '📚', '✅', '🗓️', '🧾', '🏷️', '📎',
  '💬', '📢', '📧', '🤝', '👥', '🔔', '🛎️', '👋',
  '🧭', '🚀', '🛡️', '⚖️', '💰', '🛒', '🏭', '🌐',
]

onMounted(refresh)

async function refresh() {
  loading.value = true
  try {
    bots.value = (await listBots()) || []
  } finally {
    loading.value = false
  }
}

/** 确保下拉选项就绪（首次打开弹窗时懒加载亦可） */
async function ensureAgentOptions() {
  if (agentOptions.value.length) return
  agentOptions.value = (await listAgentOptions().catch(() => [])) || []
}

async function openCreate() {
  form.value = { botUserId: '', enabled: true, nickname: '机器人' }
  faceUrlAuto = false
  await ensureAgentOptions()
  dialog.value = true
}

async function openEdit(row: ImBot) {
  // 输入框只持有 bot_ 后缀（前缀固定展示在输入框左侧），保存时再组装完整账号
  form.value = { ...row, enabled: row.status === 0, botUserId: (row.botUserId || '').replace(/^bot_/, '') }
  faceUrlAuto = false
  await ensureAgentOptions()
  // 存量机器人缺头像：带出绑定智能体的头像（emoji 自动转图片）
  if (!form.value.faceUrl && form.value.agentCode) await applyAgentAvatar(form.value.agentCode)
  dialog.value = true
}

/** 换绑智能体：头像此前为自动带出（或为空）时跟随新智能体刷新，手动设置过则保留 */
function onAgentChange(code: string) {
  if (!form.value.faceUrl || faceUrlAuto) void applyAgentAvatar(code)
}

/** 智能体头像 → OpenIM face_url：空→空；emoji 画 PNG 上传；聊天客户端不可寻址的站内路径（如 /ai/agent/asset/）转传；其余原样 */
async function applyAgentAvatar(code?: string) {
  const avatar = (agentOptions.value.find((o) => o.agentCode === code)?.avatar || '').trim()
  if (!avatar) {
    form.value.faceUrl = ''
    faceUrlAuto = true
    return
  }
  avatarBusy.value = true
  try {
    form.value.faceUrl = await toFaceUrl(avatar)
    faceUrlAuto = true
  } catch {
    /* 转换/上传失败保留原头像，不打断编辑 */
  } finally {
    avatarBusy.value = false
  }
}

/** 手动选内置图标：同样转 PNG 上传成真实图片地址（OpenIM face_url 需 URL） */
async function pickEmoji(emoji: string) {
  avatarBusy.value = true
  try {
    form.value.faceUrl = await toFaceUrl(emoji)
    faceUrlAuto = false
  } catch {
    ElMessage.error('头像生成失败，请重试')
  } finally {
    avatarBusy.value = false
  }
}

/** 手动上传图片：直接落站内地址 */
async function onAvatarFile(file: any) {
  const raw = file?.raw as File | undefined
  if (!raw) return
  if (raw.size > 2 * 1024 * 1024) {
    ElMessage.error('头像不能超过 2MB')
    return
  }
  avatarBusy.value = true
  try {
    form.value.faceUrl = (await uploadBotAsset(raw)) as unknown as string
    faceUrlAuto = false
  } finally {
    avatarBusy.value = false
  }
}

function clearAvatar() {
  form.value.faceUrl = ''
  faceUrlAuto = false
}

/** 头像值 → face_url 地址：emoji（非路径/URL）画 128×128 PNG 上传；站内非常规前缀转传；
 * http(s) 与 /fs/、/im/（聊天客户端 assetUrl 可寻址）原样返回 */
async function toFaceUrl(avatar: string): Promise<string> {
  if (!/^(https?:\/\/|\/)/.test(avatar)) {
    return uploadFile(await emojiBlob(avatar), 'png')
  }
  if (/^(https?:\/\/|\/(fs|im)\/)/.test(avatar)) return avatar
  const resp = await fetch(resolveSrc(avatar))
  if (!resp.ok) throw new Error(`头像拉取失败：${resp.status}`)
  const blob = await resp.blob()
  const ext = blob.type === 'image/jpeg' ? 'jpg' : blob.type === 'image/gif' ? 'gif'
    : blob.type === 'image/webp' ? 'webp' : 'png'
  return uploadFile(blob, ext)
}

/** emoji → 128×128 透明底 PNG（与聊天客户端字头像同款 canvas 画法，颜色字体随系统） */
function emojiBlob(emoji: string): Promise<Blob> {
  const size = 128
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = canvas.getContext('2d')
  if (!ctx) return Promise.reject(new Error('canvas 不可用'))
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.font = '96px "Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", sans-serif'
  ctx.fillText(emoji, size / 2, size / 2 + 6)
  return new Promise((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('canvas 导出失败'))), 'image/png'))
}

async function uploadFile(blob: Blob, ext: string): Promise<string> {
  const file = new File([blob], `bot-avatar.${ext}`, { type: blob.type || 'image/png' })
  return (await uploadBotAsset(file)) as unknown as string
}

/** 站内相对路径加 /api 前缀（img/fetch 不携带 token，走免登录素材端点），绝对地址原样 */
function resolveSrc(url?: string) {
  const u = (url || '').trim()
  if (!u || /^(https?:|data:|blob:)/i.test(u)) return u
  return /^\/(fs|im|system|ai)\//.test(u) ? '/api' + u : u
}

function isImgSrc(v?: string) {
  return !!v && /^(https?:\/\/|\/)/.test(v.trim())
}

function agentOf(code?: string) {
  return agentOptions.value.find((o) => o.agentCode === code)
}

function agentAvatar(code?: string) {
  return agentOf(code)?.avatar || ''
}

/** 表格展示：智能体名（编码）；选项未加载/已删除时回退编码 */
function agentLabel(code?: string) {
  const a = agentOf(code)
  return a ? `${a.name || a.agentCode}（${a.agentCode}）` : code || '—'
}

/** 输入框后缀 → 完整 bot 账号：剔除误敲的前缀后补 bot_；空 = 留空自动生成 */
function botAccount(): string {
  const suffix = (form.value.botUserId || '').trim().replace(/^bot_+/, '')
  return suffix ? 'bot_' + suffix : ''
}

async function save() {
  if (!form.value.agentCode) {
    ElMessage.warning('必须绑定智能体编码')
    return
  }
  if (avatarBusy.value) {
    ElMessage.warning('头像处理中，请稍候')
    return
  }
  saving.value = true
  try {
    await saveBot({
      id: form.value.id,
      botUserId: botAccount(),
      nickname: form.value.nickname,
      faceUrl: form.value.faceUrl,
      agentCode: form.value.agentCode,
      triggerKeywords: form.value.triggerKeywords,
      groupIds: form.value.groupIds,
      status: form.value.enabled ? 0 : 1
    })
    ElMessage.success('已保存并同步注册进实例')
    dialog.value = false
    await refresh()
  } finally {
    saving.value = false
  }
}

async function remove(row: ImBot) {
  await deleteBot(row.id!)
  ElMessage.success('已删除')
  await refresh()
}
</script>

<style scoped>
.page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1000px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 8px 0 0;
}
.agent-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.agent-mini {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  object-fit: cover;
  flex-shrink: 0;
}
.agent-mini-emoji {
  font-size: 15px;
  line-height: 1;
}
.agent-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.opt-avatar {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  object-fit: cover;
  flex-shrink: 0;
}
.opt-emoji {
  font-size: 15px;
  line-height: 1;
}
.avatar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
}
.avatar-preview {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  overflow: hidden;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light);
}
.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.avatar-emoji {
  font-size: 22px;
  line-height: 1;
}
.avatar-empty {
  opacity: 0.45;
}
.field-hint {
  width: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 2px;
}
.emoji-grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 2px;
}
.emoji-item {
  border: none;
  background: transparent;
  font-size: 20px;
  line-height: 1;
  padding: 4px;
  border-radius: 6px;
  cursor: pointer;
}
.emoji-item:hover {
  background: var(--el-fill-color);
}
</style>
