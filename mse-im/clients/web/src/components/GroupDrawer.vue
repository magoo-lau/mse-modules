<template>
  <div v-if="visible" class="gd-mask" @click.self="$emit('close')">
    <aside class="gd">
      <div class="gd-head">
        <b>{{ groupName || '群资料' }}</b>
        <button class="x" @click="$emit('close')">×</button>
      </div>

      <div class="gd-body">
        <!-- 群信息（群主/管理员可编辑） -->
        <div class="gd-sec">
          <div class="gd-row" :title="canManage ? '点击修改群图标' : ''" @click="editAvatar">
            <span class="gd-k">群图标</span>
            <img v-if="groupInfo?.faceURL" class="face gd-face" :src="assetUrl(groupInfo.faceURL)" alt="" />
            <span v-else class="face char gd-face">{{ (groupInfo?.groupName || groupName || '?').charAt(0) }}</span>
            <span v-if="canManage" class="gd-edit">{{ avatarBusy ? '…' : '✎' }}</span>
          </div>
          <input ref="avatarInput" type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden @change="onAvatarPicked" />
          <div class="gd-row" @click="editName">
            <span class="gd-k">群名称</span>
            <span class="gd-v">{{ groupInfo?.groupName || groupName || '—' }}</span>
            <span v-if="canManage" class="gd-edit">✎</span>
          </div>
          <div class="gd-row col" @click="editNotice">
            <span class="gd-k">群公告</span>
            <span class="gd-v pre">{{ groupInfo?.notification || (canManage ? '点击设置群公告' : '暂无公告') }}</span>
            <span v-if="canManage" class="gd-edit">✎</span>
          </div>
          <div v-if="canManage" class="gd-row">
            <span class="gd-k">全员禁言</span>
            <label class="switch" @click.stop>
              <input type="checkbox" :checked="groupInfo?.status === 3" @change="toggleGroupMute" />
              <i></i>
            </label>
          </div>
        </div>

        <div class="gd-sub">
          <span>群成员（{{ members.length }}）</span>
          <button v-if="canInvite" class="primary sm" @click="showInvite = true">＋ 邀请</button>
        </div>
        <div class="gd-members">
          <div v-for="m in members" :key="m.userID" class="gd-m" @contextmenu.prevent="openMemberMenu($event, m)"
               @click="$emit('show-profile', m)">
            <img v-if="m.faceURL" class="face click" :src="assetUrl(m.faceURL)" alt="" />
            <span v-else class="face char click" :class="{ bot: isBot(m) }">{{ isBot(m) ? '🤖' : (m.nickname || m.userID || '?').charAt(0) }}</span>
            <span class="gd-m-name">{{ m.nickname || m.userID }}</span>
            <span v-if="m.muteEndTime > Date.now()" class="badge-muted">禁言中</span>
            <span v-if="isBot(m)" class="badge-bot">机器人</span>
            <span v-else-if="m.roleLevel === 100" class="badge-owner">群主</span>
            <span v-else-if="m.roleLevel === 60" class="badge-owner">管理员</span>
          </div>
          <div v-if="!members.length && !loading" class="gd-empty">暂无成员数据</div>
          <p v-if="!isDeptGroup" class="gd-hint">提示：点成员看资料；右键成员可管理（群主/管理员）；部门群由系统自动维护</p>
        </div>
      </div>

      <!-- 底部群操作 -->
      <div class="gd-foot">
        <button v-if="isOwner && !isDeptGroup" class="danger" @click="doDismiss">解散群聊</button>
        <button v-if="isOwner" class="sm" @click="promptTransfer">转让群主</button>
        <button v-if="!isDeptGroup" class="danger sm" @click="doQuit">退出群聊</button>
      </div>
      <p v-if="errorMsg" class="gd-err">{{ errorMsg }}</p>
      <p v-else class="gd-hint2">邀请/管理权限：仅群主 / 群管理员（与 mse-im 服务端校验一致）</p>
    </aside>
  </div>

  <PickerModal :visible="showInvite" title="邀请进群" :depts="depts" :users="users" :bots="bots"
               :exclude-ids="memberIds" :loading="contactsLoading" @close="showInvite = false" @confirm="doInvite" />

  <!-- 成员右键菜单 -->
  <ContextMenu :visible="!!memberMenu" :x="memberMenu?.x || 0" :y="memberMenu?.y || 0"
               :items="memberMenuItems" @close="memberMenu = null" @select="onMemberMenu" />

  <!-- 转让群主选择 -->
  <div v-if="transferring" class="tf-mask" @click.self="transferring = null">
    <div class="tf">
      <div class="tf-head">转让群主给</div>
      <div class="tf-body">
        <div v-for="m in transferCandidates" :key="m.userID" class="tf-item" @click="doTransfer(m)">
          <img v-if="m.faceURL" class="face" :src="assetUrl(m.faceURL)" alt="" />
          <span v-else class="face char">{{ (m.nickname || m.userID || '?').charAt(0) }}</span>
          <span>{{ m.nickname || m.userID }}</span>
        </div>
        <div v-if="!transferCandidates.length" class="tf-empty">没有可转让的普通成员</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { CbEvents } from '@openim/wasm-client-sdk'
import type { WasmSdk } from '@openim/wasm-client-sdk'
import * as mse from '../mse'
import { assetUrl } from '../chat/util'
import PickerModal from './PickerModal.vue'
import ContextMenu, { type CtxItem } from '../chat/ContextMenu.vue'

const props = defineProps<{
  visible: boolean
  groupId: string
  groupName: string
  meId: string
  sdk: WasmSdk | null
  depts: any[]
  users: mse.ContactUser[]
  bots: mse.ContactBot[]
  /** 部门群 groupId 集（对账自动维护，不提供退群/解散入口） */
  deptGroupIds?: string[]
  /** 通讯录加载中（邀请弹窗展示「加载中」而非「通讯录为空」） */
  contactsLoading?: boolean
}>()

const emit = defineEmits<{ close: []; changed: []; renamed: []; 'avatar-changed': [faceURL: string]; 'show-profile': [member: any] }>()

const members = ref<any[]>([])
const groupInfo = ref<any>(null)
const loading = ref(false)
const showInvite = ref(false)
const errorMsg = ref('')
const memberMenu = ref<{ x: number; y: number; member: any } | null>(null)
const transferring = ref<null | 'pick'>(null)
const avatarBusy = ref(false)
const avatarInput = ref<HTMLInputElement | null>(null)

const memberIds = computed(() => members.value.map(m => m.userID))
const me = computed(() => members.value.find(m => m.userID === props.meId))
/** 管理权限：群主(100)/管理员(60)——与 mse-im 服务端强制校验一致 */
const isOwner = computed(() => me.value?.roleLevel === 100)
const canManage = computed(() => !!me.value && (me.value.roleLevel === 100 || me.value.roleLevel === 60))
const canInvite = canManage
/** 部门群：系统对账自动维护（改名会被覆盖、退群会被拉回），隐藏对应入口 */
const isDeptGroup = computed(() => (props.deptGroupIds || []).includes(props.groupId))
const transferCandidates = computed(() => members.value.filter(m => m.userID !== props.meId && !isBot(m) && m.roleLevel !== 100))

const memberMenuItems = computed<CtxItem[]>(() => {
  const m = memberMenu.value?.member
  if (!m || !canManage.value) return []
  if (m.userID === props.meId) return []
  const items: CtxItem[] = []
  if (isOwner.value && !isBot(m) && m.roleLevel !== 100) items.push({ key: 'transfer', label: '转让群主' })
  if (!isBot(m) && m.roleLevel !== 100) {
    items.push({ key: 'mute1h', label: '禁言 1 小时' })
    items.push({ key: 'mute24h', label: '禁言 24 小时' })
    items.push({ key: 'unmute', label: '解除禁言' })
  }
  items.push({ key: 'kick', label: '移出群聊', danger: true })
  return items
})

function isBot(m: any) { return String(m.userID || '').startsWith('bot_') }

watch(() => props.visible, (v) => {
  if (v) {
    members.value = []
    groupInfo.value = null
    errorMsg.value = ''
    transferring.value = null
    bindMemberEvents()
    loadMembers()
    loadGroupInfo()
  } else {
    showInvite.value = false
    memberMenu.value = null
    unbindMemberEvents()
  }
})

/* ---------- 群成员事件：邀请/踢人等由服务端发起时本地缓存靠推送同步，打开期间挂监听即时刷新 ---------- */

const memberEventHandlers: Array<[string, (d: any) => void]> = []

function bindMemberEvents() {
  const sdk = props.sdk
  if (!sdk || memberEventHandlers.length) return
  const reloadIfMatch = (d: any) => {
    const p = d?.data ?? d
    const gid = p?.groupID || ''
    // 事件携带群号时按群过滤；取不到群号宁多刷一次
    if (!gid || gid === props.groupId) loadMembers()
  }
  for (const evt of [CbEvents.OnGroupMemberAdded, CbEvents.OnGroupMemberDeleted, CbEvents.OnGroupMemberInfoChanged]) {
    const h = reloadIfMatch
    sdk.on(evt as any, h as any)
    memberEventHandlers.push([evt as string, h])
  }
}

function unbindMemberEvents() {
  const sdk = props.sdk
  if (sdk) {
    for (const [evt, h] of memberEventHandlers) sdk.off(evt as any, h as any)
  }
  memberEventHandlers.length = 0
}

onBeforeUnmount(unbindMemberEvents)

async function loadGroupInfo() {
  if (!props.sdk || !props.groupId) return
  try {
    const res: any = await props.sdk.getSpecifiedGroupsInfo([props.groupId])
    const list = Array.isArray(res) ? res : (res?.data ?? [])
    groupInfo.value = list[0] || null
  } catch { /* 信息缺失不阻断 */ }
}

async function loadMembers() {
  if (!props.sdk || !props.groupId) return
  loading.value = true
  try {
    // SDK 形态兼容：位置参数或对象参数
    let res: any
    try {
      res = await (props.sdk as any).getGroupMemberList(props.groupId, 0, 500, 0)
    } catch {
      res = await (props.sdk as any).getGroupMemberList({ groupID: props.groupId, offset: 0, count: 500, filter: 0 })
    }
    members.value = Array.isArray(res) ? res : (res?.data?.members ?? res?.data ?? res?.members ?? [])
  } catch (e: any) {
    errorMsg.value = e?.message || String(e)
    members.value = []
  } finally {
    loading.value = false
  }
}

async function doInvite(payload: { name: string; userIds: number[]; botUserIds: string[] }) {
  try {
    await mse.inviteMembers(props.groupId, { userIds: payload.userIds, botUserIds: payload.botUserIds })
    showInvite.value = false
    await loadMembers()
    emit('changed')
    // 服务端邀请已生效，但本地缓存可能晚于事件推送到达——延迟补刷兜底
    setTimeout(() => { if (props.visible) loadMembers() }, 1200)
  } catch (e: any) {
    errorMsg.value = e?.message || String(e)
  }
}

/* ---------- 群信息编辑 ---------- */

const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`)

/** 群图标：SDK uploadFile 走 /object/* 直传（与图片消息同链路），setGroupInfo 补丁式只改 faceURL */
function editAvatar() {
  if (!canManage.value || avatarBusy.value) return
  avatarInput.value?.click()
}

async function onAvatarPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !props.sdk) return
  if (file.size > 5 * 1024 * 1024) {
    errorMsg.value = '修改群图标失败：图片不能超过 5MB'
    return
  }
  await run('修改群图标', async () => {
    avatarBusy.value = true
    try {
      const res: any = await props.sdk!.uploadFile({ name: file.name, contentType: file.type || 'image/png', uuid: uuid(), file })
      const url: string = res?.data?.url || res?.url || (Array.isArray(res) ? res[0]?.data?.url : '') || ''
      if (!url) throw new Error('上传失败：未返回地址')
      await props.sdk!.setGroupInfo({ groupID: props.groupId, faceURL: url })
      groupInfo.value = { ...groupInfo.value, faceURL: url }
      emit('avatar-changed', url)
    } finally {
      avatarBusy.value = false
    }
  })
}

function editName() {
  if (!canManage.value) return
  const name = window.prompt('修改群名称', groupInfo.value?.groupName || props.groupName || '')
  if (!name || !name.trim()) return
  run('修改群名', async () => {
    await props.sdk!.setGroupInfo({ groupID: props.groupId, groupName: name.trim() })
    groupInfo.value = { ...groupInfo.value, groupName: name.trim() }
    emit('renamed')
  })
}

function editNotice() {
  if (!canManage.value) return
  const notice = window.prompt('设置群公告', groupInfo.value?.notification || '')
  if (notice == null) return
  run('设置公告', async () => {
    await props.sdk!.setGroupInfo({ groupID: props.groupId, notification: notice.trim() })
    groupInfo.value = { ...groupInfo.value, notification: notice.trim() }
  })
}

async function toggleGroupMute() {
  const muted = groupInfo.value?.status === 3
  await run(muted ? '解除全员禁言' : '全员禁言', async () => {
    await props.sdk!.changeGroupMute({ groupID: props.groupId, isMute: !muted })
    groupInfo.value = { ...groupInfo.value, status: muted ? 0 : 3 }
  })
}

/* ---------- 成员管理 ---------- */

function openMemberMenu(e: MouseEvent, m: any) {
  if (!canManage.value || m.userID === props.meId) return
  memberMenu.value = { x: e.clientX, y: e.clientY, member: m }
}

async function onMemberMenu(key: string) {
  const m = memberMenu.value?.member
  if (!m || !props.sdk) return
  switch (key) {
    case 'transfer':
      transferring.value = 'pick'
      break
    case 'mute1h':
      await run('禁言', () => props.sdk!.changeGroupMemberMute({ groupID: props.groupId, userID: m.userID, mutedSeconds: 3600 }))
      break
    case 'mute24h':
      await run('禁言', () => props.sdk!.changeGroupMemberMute({ groupID: props.groupId, userID: m.userID, mutedSeconds: 86400 }))
      break
    case 'unmute':
      await run('解除禁言', () => props.sdk!.changeGroupMemberMute({ groupID: props.groupId, userID: m.userID, mutedSeconds: 0 }))
      break
    case 'kick':
      if (!window.confirm(`将 ${m.nickname || m.userID} 移出群聊？`)) return
      await run('移出群聊', () => props.sdk!.kickGroupMember({ groupID: props.groupId, userIDList: [m.userID], reason: '' }))
      break
  }
}

async function doTransfer(m: any) {
  transferring.value = null
  if (!m) return
  await run('转让群主', async () => {
    await props.sdk!.transferGroupOwner({ groupID: props.groupId, newOwnerUserID: m.userID })
    await loadMembers()
  })
}

function promptTransfer() {
  transferring.value = 'pick'
}

/* ---------- 群操作 ---------- */

async function doQuit() {
  if (!window.confirm('确定退出该群聊？')) return
  if (await run('退出群聊', () => props.sdk!.quitGroup(props.groupId))) {
    emit('changed')
    emit('close')
  }
}

async function doDismiss() {
  if (!window.confirm('确定解散该群聊？此操作不可恢复。')) return
  if (await run('解散群聊', () => props.sdk!.dismissGroup(props.groupId))) {
    emit('changed')
    emit('close')
  }
}

/** 统一执行 + 错误提示；返回是否成功 */
async function run(label: string, fn: () => Promise<any>): Promise<boolean> {
  try {
    await fn()
    errorMsg.value = ''
    return true
  } catch (e: any) {
    errorMsg.value = `${label}失败：${e?.errMsg || e?.message || e}`
    return false
  }
}
</script>

<style scoped>
.gd-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .25); z-index: 50; display: flex; justify-content: flex-end; }
.gd { width: 320px; max-width: 86vw; background: var(--im-panel-bg); height: 100vh; display: flex; flex-direction: column; box-shadow: -6px 0 24px var(--im-shadow); }
.gd-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--im-border); }
.gd-head b { font-size: 15px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.gd-head .x { border: none; background: transparent; font-size: 20px; color: var(--im-text-2); padding: 0 4px; cursor: pointer; }
.gd-body { flex: 1; overflow: auto; min-height: 0; }

.gd-sec { padding: 6px 12px; border-bottom: 1px solid var(--im-border); }
.gd-row { display: flex; align-items: center; gap: 8px; padding: 9px 6px; font-size: 13px; cursor: pointer; border-radius: 6px; }
.gd-row:hover { background: var(--im-hover); }
.gd-row.col { align-items: flex-start; flex-direction: column; gap: 4px; }
.gd-row .gd-k { color: var(--im-text-2); flex: none; }
.gd-row.col .gd-k { color: var(--im-text-2); }
.gd-row .gd-v { flex: 1; min-width: 0; text-align: right; }
.gd-row.col .gd-v { text-align: left; white-space: pre-wrap; word-break: break-word; font-size: 12px; color: var(--im-text-2); }
.gd-row .gd-v.pre { max-height: 60px; overflow: hidden; }
.gd-edit { flex: none; color: var(--im-text-2); font-size: 12px; }
.switch { position: relative; width: 34px; height: 19px; flex: none; cursor: pointer; }
.switch input { display: none; }
.switch i { position: absolute; inset: 0; border-radius: 10px; background: #c8cbd2; transition: background .15s; }
.switch i::after { content: ''; position: absolute; top: 2px; left: 2px; width: 15px; height: 15px; border-radius: 50%; background: #fff; transition: transform .15s; }
.switch input:checked + i { background: var(--im-primary); }
.switch input:checked + i::after { transform: translateX(15px); }

.gd-sub { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px 4px; font-size: 12px; color: var(--im-text-2); }
.gd-sub .sm { padding: 4px 10px; font-size: 12px; }
.gd-members { padding: 6px 10px 12px; }
.gd-m { display: flex; align-items: center; gap: 10px; padding: 6px 8px; border-radius: 8px; font-size: 13px; cursor: pointer; }
.gd-m:hover { background: var(--im-hover); }
.gd-m-name { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.face { width: 32px; height: 32px; border-radius: 7px; flex: none; object-fit: cover; }
.face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 14px; }
.face.char.bot { background: #00b04f; }
.gd-face { margin-left: auto; width: 36px; height: 36px; border-radius: 8px; font-size: 16px; }
.badge-bot, .badge-owner, .badge-muted { font-size: 10px; border-radius: 8px; padding: 0 6px; line-height: 16px; flex: none; }
.badge-bot { color: #00b04f; border: 1px solid #00b04f; }
.badge-owner { color: var(--im-primary); border: 1px solid var(--im-primary); }
.badge-muted { color: #e6a23c; border: 1px solid #e6a23c; }
.gd-empty { color: var(--im-text-2); font-size: 12px; text-align: center; padding: 24px 0; }
.gd-err { margin: 0; padding: 8px 16px 0; font-size: 12px; color: #e34d59; word-break: break-all; }
.gd-hint { margin: 6px 4px 0; font-size: 11px; color: var(--im-text-2); }
.gd-hint2 { margin: 0; padding: 10px 16px 14px; font-size: 11px; color: var(--im-text-2); border-top: 1px solid var(--im-border); }

.gd-foot { display: flex; gap: 8px; padding: 10px 16px; border-top: 1px solid var(--im-border); flex: none; }
.gd-foot button { flex: 1; }
.gd-foot .danger { color: #e34d59; }
.gd-foot .sm { padding: 7px 10px; font-size: 12px; }

.tf-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .3); z-index: 72; display: flex; align-items: center; justify-content: center; }
.tf { width: 300px; max-height: 60vh; background: var(--im-panel-bg); border-radius: 12px; display: flex; flex-direction: column; overflow: hidden; box-shadow: 0 8px 32px var(--im-shadow); }
.tf-head { padding: 12px 16px; font-weight: 600; }
.tf-body { overflow: auto; padding: 4px 10px 12px; }
.tf-item { display: flex; align-items: center; gap: 8px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.tf-item:hover { background: var(--im-hover); }
.tf-empty { color: var(--im-text-2); font-size: 12px; text-align: center; padding: 20px 0; }
</style>
