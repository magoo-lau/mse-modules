<template>
  <div v-if="visible" class="fp-mask" @click.self="$emit('close')">
    <div class="fp">
      <div class="fp-head">{{ title }}</div>
      <div class="fp-tabs">
        <button v-for="t in ['会话', '联系人', '群聊']" :key="t" class="fp-tab" :class="{ active: tab === t }" @click="tab = t">{{ t }}</button>
      </div>
      <div class="fp-search"><input v-model="keyword" placeholder="搜索" /></div>
      <div class="fp-body">
        <template v-if="tab === '会话'">
          <div v-for="c in convList" :key="c.conversationID" class="fp-item" :class="{ sel: isSel('c' + c.conversationID) }" @click="pick('c' + c.conversationID, { sourceID: c.userID || c.groupID, sessionType: c.groupID ? 3 : 1, name: c.showName })">
            <img v-if="c.faceURL" class="face" :src="assetUrl(c.faceURL)" alt="" />
            <span v-else class="face char" :class="{ group: !!c.groupID }">{{ (c.showName || '?').charAt(0) }}</span>
            <span class="nm">{{ c.showName }}</span>
            <span class="tag">{{ c.groupID ? '群聊' : '单聊' }}</span>
          </div>
          <div v-if="!convList.length" class="fp-empty">暂无会话</div>
        </template>
        <template v-else-if="tab === '联系人'">
          <template v-if="!keyword.trim()">
            <template v-for="row in treeRows" :key="row.key">
              <div v-if="row.kind === 'dept'" class="fp-dept" :style="{ paddingLeft: 8 + row.level * 16 + 'px' }" @click="toggleDept(row.id!)">
                <i class="arrow" :class="{ open: isOpen(row.id!) }">
                  <svg viewBox="0 0 16 16"><path d="M6 3.5 10.5 8 6 12.5" /></svg>
                </i>
                <span class="nm">{{ row.name }}</span>
                <span class="tag">({{ row.count }})</span>
              </div>
              <div v-else class="fp-item" :style="{ paddingLeft: 8 + row.level * 16 + 'px' }" :class="{ sel: isSel('u' + row.user!.userId) }"
                   @click="pick('u' + row.user!.userId, { sourceID: String(row.user!.userId), sessionType: 1, name: row.user!.nickname })">
                <img v-if="row.user!.avatar" class="face" :src="assetUrl(row.user!.avatar)" alt="" />
                <span v-else class="face char">{{ row.user!.nickname.charAt(0) }}</span>
                <span class="nm">{{ row.user!.nickname }}</span>
              </div>
            </template>
            <div v-if="botList.length" class="fp-dept group" @click="botsOpen = !botsOpen">
              <i class="arrow" :class="{ open: botsOpen }">
                <svg viewBox="0 0 16 16"><path d="M6 3.5 10.5 8 6 12.5" /></svg>
              </i>
              <span class="nm">机器人</span>
              <span class="tag">({{ botList.length }})</span>
            </div>
            <template v-if="botsOpen">
              <div v-for="b in botList" :key="b.botUserId" class="fp-item" style="padding-left: 24px" :class="{ sel: isSel('b' + b.botUserId) }" @click="pick('b' + b.botUserId, { sourceID: b.botUserId, sessionType: 1, name: b.nickname })">
                <span class="face char bot">🤖</span>
                <span class="nm">{{ b.nickname }}</span>
                <span class="tag">机器人</span>
              </div>
            </template>
            <div v-if="!treeRows.length && !botList.length" class="fp-empty">通讯录为空</div>
          </template>
          <template v-else>
            <div v-for="u in userList" :key="u.userId" class="fp-item" :class="{ sel: isSel('u' + u.userId) }" @click="pick('u' + u.userId, { sourceID: String(u.userId), sessionType: 1, name: u.nickname })">
              <img v-if="u.avatar" class="face" :src="assetUrl(u.avatar)" alt="" />
              <span v-else class="face char">{{ u.nickname.charAt(0) }}</span>
              <span class="nm">{{ u.nickname }}</span>
              <span class="tag">{{ deptNameOf(u.deptId) }}</span>
            </div>
            <div v-for="b in botList" :key="b.botUserId" class="fp-item" :class="{ sel: isSel('b' + b.botUserId) }" @click="pick('b' + b.botUserId, { sourceID: b.botUserId, sessionType: 1, name: b.nickname })">
              <span class="face char bot">🤖</span>
              <span class="nm">{{ b.nickname }}</span>
              <span class="tag">机器人</span>
            </div>
            <div v-if="!userList.length && !botList.length" class="fp-empty">无匹配联系人</div>
          </template>
        </template>
        <template v-else>
          <div v-for="g in groupList" :key="g.groupID" class="fp-item" :class="{ sel: isSel('g' + g.groupID) }" @click="pick('g' + g.groupID, { sourceID: g.groupID, sessionType: 3, name: g.groupName })">
            <img v-if="g.groupFaceURL" class="face" :src="assetUrl(g.groupFaceURL)" alt="" />
            <span v-else class="face char group">{{ (g.groupName || g.groupID || '?').charAt(0) }}</span>
            <span class="nm">{{ g.groupName || g.groupID }}</span>
          </div>
          <div v-if="!groupList.length" class="fp-empty">暂无群聊</div>
        </template>
      </div>
      <div class="fp-foot">
        <button @click="$emit('close')">取消</button>
        <button class="primary" :disabled="!selectedCount" :busy="busy" @click="confirm">{{ busy ? '发送中…' : selectedCount > 1 ? `发送(${selectedCount})` : '发送' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { assetUrl } from './util'

/** 转发/分享目标（sourceID：单聊=userId/botUserId、群聊=groupID） */
export type ForwardTarget = { sourceID: string; sessionType: 1 | 3; name: string }

const props = defineProps<{
  visible: boolean
  title?: string
  conversations: any[]
  users: any[]
  bots: any[]
  groups: any[]
  /** 部门列表：联系人页签按组织架构树展示（缺省退化为平铺） */
  depts?: any[]
  busy?: boolean
  /** 多选：点击切换选中、可跨页签累计；单选行为与历史一致。send 统一返回目标数组（单选=长度 1） */
  multiple?: boolean
}>()

const emit = defineEmits<{ close: []; send: [targets: ForwardTarget[]] }>()

const tab = ref('会话')
const keyword = ref('')
const targetKey = ref('')
const target = ref<ForwardTarget | null>(null)
const multiTargets = ref<Map<string, ForwardTarget>>(new Map())
const botsOpen = ref(true)
const openDepts = ref<Set<string>>(new Set())

watch(() => props.visible, v => {
  if (v) { keyword.value = ''; targetKey.value = ''; target.value = null; multiTargets.value = new Map(); tab.value = '会话'; openRoots() }
})

/** 部门数据晚于弹窗打开到达（通讯录预热未完成）时补展开根部门 */
watch(() => (props.depts || []).length, () => { if (!openDepts.value.size) openRoots() })

/** 根部门默认展开（与发起群聊选择器一致） */
function openRoots() {
  const depts = props.depts || []
  if (!depts.length) return
  const roots = depts.filter(d => !d.parentId || d.parentId === 0).map(d => String(d.id))
  openDepts.value = new Set(roots.length ? roots : [String(depts[0].id)])
}

function isOpen(id: string) { return openDepts.value.has(id) }
function toggleDept(id: string) {
  const next = new Set(openDepts.value)
  next.has(id) ? next.delete(id) : next.add(id)
  openDepts.value = next
}

function pick(key: string, t: ForwardTarget) {
  if (!props.multiple) { targetKey.value = key; target.value = t; return }
  const next = new Map(multiTargets.value)
  if (next.has(key)) next.delete(key)
  else next.set(key, t)
  multiTargets.value = next
}

function isSel(key: string): boolean {
  return props.multiple ? multiTargets.value.has(key) : targetKey.value === key
}

const selectedCount = computed(() => (props.multiple ? multiTargets.value.size : target.value ? 1 : 0))

function confirm() {
  if (props.multiple) {
    if (multiTargets.value.size) emit('send', [...multiTargets.value.values()])
  } else if (target.value) {
    emit('send', [target.value])
  }
}

const match = (s: string | undefined) => (s || '').toLowerCase().includes(keyword.value.trim().toLowerCase())
const convList = computed(() => props.conversations.filter(c => match(c.showName)))
const userList = computed(() => props.users.filter(u => match(u.nickname)))
const botList = computed(() => props.bots.filter(b => match(b.nickname)))
const groupList = computed(() => props.groups.filter(g => match(g.groupName)))

/* ---------- 联系人组织架构树（与通讯录 / 发起群聊选择器同款拼法） ---------- */

/** 直挂用户按部门分组 */
const usersByDept = computed(() => {
  const m = new Map<any, any[]>()
  for (const u of props.users) {
    if (u.deptId == null) continue
    const k = String(u.deptId)
    if (!m.has(k)) m.set(k, [])
    m.get(k)!.push(u)
  }
  return m
})

/** 部门树（父缺失挂根） */
const deptTree = computed(() => {
  const depts = props.depts || []
  const children = new Map<string, { id: string; name: string }[]>()
  const roots: { id: string; name: string }[] = []
  const seen = new Set(depts.map(d => String(d.id)))
  for (const d of depts) {
    const pid = String(d.parentId ?? '0')
    const node = { id: String(d.id), name: d.name }
    if (pid === '0' || !seen.has(pid)) roots.push(node)
    else {
      if (!children.has(pid)) children.set(pid, [])
      children.get(pid)!.push(node)
    }
  }
  return { children, roots }
})

/** 部门人数 = 子树汇总（本部门直挂 + 全部下级部门；path 防脏数据成环死循环） */
const deptTotals = computed(() => {
  const { children, roots } = deptTree.value
  const totals = new Map<string, number>()
  const visit = (id: string, path: Set<string>): number => {
    if (path.has(id)) return 0
    path.add(id)
    let n = (usersByDept.value.get(id) || []).length
    for (const c of children.get(id) || []) n += visit(c.id, path)
    path.delete(id)
    totals.set(id, n)
    return n
  }
  for (const r of roots) visit(r.id, new Set())
  return totals
})

/** 部门树 DFS 扁平行（deptId 空的用户不展示在树上，搜索可命中） */
interface TreeRow { kind: 'dept' | 'user'; key: string; id?: string; name?: string; level: number; count?: number; user?: any }

const treeRows = computed<TreeRow[]>(() => {
  const { children, roots } = deptTree.value
  const totals = deptTotals.value
  const out: TreeRow[] = []
  const walk = (nodes: { id: string; name: string }[], level: number) => {
    for (const n of nodes) {
      out.push({ kind: 'dept', key: 'd' + n.id, id: n.id, name: n.name, level, count: totals.get(n.id) || 0 })
      if (isOpen(n.id)) {
        for (const u of usersByDept.value.get(n.id) || []) out.push({ kind: 'user', key: 'u' + u.userId, level: level + 1, user: u })
        walk(children.get(n.id) || [], level + 1)
      }
    }
  }
  walk(roots, 0)
  return out
})

const deptNames = computed(() => new Map((props.depts || []).map(d => [d.id, d.name])))
function deptNameOf(deptId: number | null): string {
  return (deptId != null && deptNames.value.get(deptId)) || ''
}
</script>

<style scoped>
.fp-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .35); z-index: 65; display: flex; align-items: center; justify-content: center; }
.fp { width: 360px; max-width: 92vw; max-height: 76vh; background: var(--im-panel-bg); border-radius: 12px; box-shadow: 0 8px 32px var(--im-shadow); display: flex; flex-direction: column; overflow: hidden; }
.fp-head { padding: 14px 16px 6px; font-weight: 600; }
.fp-tabs { display: flex; gap: 4px; padding: 4px 16px 0; }
.fp-tab { border: none; background: transparent; padding: 6px 12px; border-radius: 8px; cursor: pointer; font-size: 13px; color: var(--im-text-2); }
.fp-tab.active { background: var(--im-active); color: var(--im-primary); font-weight: 600; }
.fp-search { padding: 8px 16px 4px; }
.fp-search input { width: 100%; padding: 7px 10px; border: 1px solid var(--im-border); border-radius: 8px; background: var(--im-input-bg); color: var(--im-text); outline: none; }
.fp-body { flex: 1; overflow: auto; padding: 6px 10px; min-height: 180px; }
.fp-item { display: flex; align-items: center; gap: 10px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; }
.fp-item:hover, .fp-item.sel { background: var(--im-hover); }
.fp-item.sel { outline: 1px solid var(--im-primary); }
.fp-item .nm { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fp-item .tag { font-size: 11px; color: var(--im-text-2); flex: none; }
.fp-dept { display: flex; align-items: center; gap: 6px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; color: var(--im-text-2); user-select: none; }
.fp-dept:hover { background: var(--im-hover); }
.fp-dept.group { margin-top: 10px; }
.fp-dept .nm { color: var(--im-text); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.fp-dept.group .nm { font-weight: 600; }
.arrow { width: 16px; height: 16px; flex: none; display: inline-flex; align-items: center; justify-content: center; color: var(--im-text-2); transition: transform .15s ease; }
.arrow svg { width: 12px; height: 12px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
.arrow.open { transform: rotate(90deg); }
.face { width: 30px; height: 30px; border-radius: 7px; flex: none; object-fit: cover; }
.face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 13px; }
.face.char.group { background: #00b04f; }
.face.char.bot { background: #00b04f; }
.fp-empty { color: var(--im-text-2); font-size: 12px; text-align: center; padding: 24px 0; }
.fp-foot { display: flex; justify-content: flex-end; gap: 8px; padding: 10px 16px; border-top: 1px solid var(--im-border); }
</style>
