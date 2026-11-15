<template>
  <div v-if="visible" class="pm-mask" @click.self="$emit('close')">
    <div class="pm">
      <div class="pm-head">{{ title }}</div>
      <div class="pm-search"><input v-model="keyword" placeholder="搜索姓名" /></div>
      <div v-if="withName" class="pm-name"><input v-model="groupName" placeholder="群名（缺省按成员昵称生成）" maxlength="64" /></div>
      <div v-if="selected.length" class="pm-chips">
        <span v-for="s in selected" :key="s.id" class="chip" :class="{ bot: s.bot }" title="点击移除" @click="toggle(s)">{{ s.name }} ×</span>
      </div>
      <div class="pm-body">
        <template v-if="keyword">
          <template v-if="matchedUsers.length">
            <div class="pm-sec">成员</div>
            <label v-for="u in matchedUsers" :key="u.userId" class="pm-item">
              <input type="checkbox" :checked="isChecked(String(u.userId))" :disabled="isExcluded(String(u.userId))" @change="toggleUser(u)" />
              <img v-if="u.avatar" class="face" :src="assetUrl(u.avatar)" alt="" />
              <span v-else class="face char">{{ u.nickname.charAt(0) }}</span>
              <span class="nm" :class="{ off: isExcluded(String(u.userId)) }">{{ u.nickname }}</span>
            </label>
          </template>
          <template v-if="matchedBots.length">
            <div class="pm-sec">机器人</div>
            <label v-for="b in matchedBots" :key="b.botUserId" class="pm-item">
              <input type="checkbox" :checked="isChecked(b.botUserId)" :disabled="isExcluded(b.botUserId)" @change="toggleBot(b)" />
              <img v-if="b.faceUrl" class="face" :src="assetUrl(b.faceUrl)" alt="" />
              <span v-else class="face char bot">🤖</span>
              <span class="nm" :class="{ off: isExcluded(b.botUserId) }">{{ b.nickname }}</span>
              <span v-if="isExcluded(b.botUserId)" class="tag">在群内</span>
            </label>
          </template>
          <div v-if="!matchedUsers.length && !matchedBots.length" class="pm-empty">无匹配</div>
        </template>
        <template v-else>
          <template v-for="row in treeRows" :key="row.key">
            <div v-if="row.kind === 'dept'" class="pm-dept" :style="{ paddingLeft: 8 + row.level * 16 + 'px' }" @click="toggleDept(row.id)">
              <i class="arrow" :class="{ open: isOpen(row.id) }">
                <svg viewBox="0 0 16 16"><path d="M6 3.5 10.5 8 6 12.5" /></svg>
              </i>
              <span class="nm">{{ row.name }}</span>
              <span class="cnt">({{ row.count }})</span>
            </div>
            <label v-else class="pm-item" :style="{ paddingLeft: 8 + row.level * 16 + 'px' }">
              <input type="checkbox" :checked="isChecked(String(row.user.userId))" :disabled="isExcluded(String(row.user.userId))" @change="toggleUser(row.user)" />
              <img v-if="row.user.avatar" class="face" :src="assetUrl(row.user.avatar)" alt="" />
              <span v-else class="face char">{{ row.user.nickname.charAt(0) }}</span>
              <span class="nm" :class="{ off: isExcluded(String(row.user.userId)) }">{{ row.user.nickname }}</span>
            </label>
          </template>
          <div v-if="bots.length" class="pm-dept group" @click="botsOpen = !botsOpen">
            <i class="arrow" :class="{ open: botsOpen }">
              <svg viewBox="0 0 16 16"><path d="M6 3.5 10.5 8 6 12.5" /></svg>
            </i>
            <span class="nm">机器人</span>
            <span class="cnt">({{ bots.length }})</span>
          </div>
          <template v-if="botsOpen">
            <label v-for="b in bots" :key="b.botUserId" class="pm-item" style="padding-left: 24px">
              <input type="checkbox" :checked="isChecked(b.botUserId)" :disabled="isExcluded(b.botUserId)" @change="toggleBot(b)" />
              <img v-if="b.faceUrl" class="face" :src="assetUrl(b.faceUrl)" alt="" />
              <span v-else class="face char bot">🤖</span>
              <span class="nm" :class="{ off: isExcluded(b.botUserId) }">{{ b.nickname }}</span>
              <span v-if="isExcluded(b.botUserId)" class="tag">在群内</span>
            </label>
          </template>
          <div v-if="!treeRows.length && !bots.length" class="pm-empty">{{ loading ? '加载中…' : '通讯录为空' }}</div>
        </template>
      </div>
      <div class="pm-foot">
        <span class="hint">已选 {{ selected.length }} 项</span>
        <button @click="$emit('close')">取消</button>
        <button class="primary" :disabled="!selected.length || busy" @click="confirm">{{ busy ? '处理中…' : '确定' }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { assetUrl } from '../chat/util'
import type { ContactBot, ContactUser } from '../mse'

interface SelItem { id: string; userId?: number; botUserId?: string; name: string; bot: boolean }

const props = withDefaults(defineProps<{
  visible: boolean
  title: string
  depts: any[]
  users: ContactUser[]
  bots: ContactBot[]
  /** 已在群内的 OpenIM userID（置灰禁选） */
  excludeIds?: string[]
  /** 建群模式：显示群名输入 */
  withName?: boolean
  /** 单选模式（名片选择等）：点击即选中该项 */
  single?: boolean
  /** 通讯录加载中：空态展示「加载中…」而非「通讯录为空」 */
  loading?: boolean
}>(), { excludeIds: () => [], withName: false, single: false, loading: false })

const emit = defineEmits<{ close: []; confirm: [payload: { name: string; userIds: number[]; botUserIds: string[] }] }>()

const keyword = ref('')
const groupName = ref('')
const selected = ref<SelItem[]>([])
const botsOpen = ref(true)
const openDepts = ref<Set<string>>(new Set())
const busy = ref(false)

watch(() => props.visible, (v) => {
  if (v) {
    keyword.value = ''
    groupName.value = ''
    selected.value = []
    busy.value = false
    openRoots()
  }
})

/** 根部门默认展开（通讯录数据晚于弹窗打开到达时也补展开） */
function openRoots() {
  if (!props.depts.length) return
  const roots = props.depts.filter(d => !d.parentId || d.parentId === 0).map(d => String(d.id))
  openDepts.value = new Set(roots.length ? roots : [String(props.depts[0].id)])
}

watch(() => props.depts.length, () => { if (!openDepts.value.size) openRoots() })

function isOpen(id: string) { return openDepts.value.has(id) }
function toggleDept(id: string) {
  const next = new Set(openDepts.value)
  next.has(id) ? next.delete(id) : next.add(id)
  openDepts.value = next
}

function isChecked(id: string) { return selected.value.some(s => s.id === id) }
function isExcluded(id: string) { return props.excludeIds.includes(id) }
function toggle(item: SelItem) {
  if (props.single) {
    // 单选：点击同一项取消，否则替换
    selected.value = isChecked(item.id) ? [] : [item]
    return
  }
  selected.value = isChecked(item.id) ? selected.value.filter(s => s.id !== item.id) : [...selected.value, item]
}
function toggleUser(u: ContactUser) {
  toggle({ id: String(u.userId), userId: u.userId, name: u.nickname, bot: false })
}
function toggleBot(b: ContactBot) {
  toggle({ id: b.botUserId, botUserId: b.botUserId, name: b.nickname, bot: true })
}

/** 直挂用户按部门分组 */
const usersByDept = computed(() => {
  const m = new Map<string, ContactUser[]>()
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
  const children = new Map<string, { id: string; name: string }[]>()
  const roots: { id: string; name: string }[] = []
  const seen = new Set(props.depts.map(d => String(d.id)))
  for (const d of props.depts) {
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

/** 部门树（DFS 扁平行：部门行 + 归属用户行；deptId 空的用户不展示在树上，搜索可命中） */
interface TreeRow { kind: 'dept' | 'user'; key: string; id?: string; name?: string; level: number; count?: number; user?: ContactUser }

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

const matchedUsers = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return []
  return props.users.filter(u => u.nickname.toLowerCase().includes(k))
})
const matchedBots = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return []
  return props.bots.filter(b => b.nickname.toLowerCase().includes(k))
})

async function confirm() {
  if (!selected.value.length || busy.value) return
  busy.value = true
  try {
    await emit('confirm', {
      name: groupName.value.trim(),
      userIds: selected.value.filter(s => !s.bot).map(s => s.userId!),
      botUserIds: selected.value.filter(s => s.bot).map(s => s.botUserId!),
    })
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.pm-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .35); z-index: 60; display: flex; align-items: center; justify-content: center; }
.pm { width: 400px; max-width: 92vw; max-height: 78vh; background: var(--im-panel-bg); border-radius: 12px; box-shadow: 0 8px 32px var(--im-shadow); display: flex; flex-direction: column; overflow: hidden; }
.pm-head { padding: 14px 16px 8px; font-weight: 600; }
.pm-search, .pm-name { padding: 4px 16px; }
.pm-search input, .pm-name input { width: 100%; padding: 7px 10px; border: 1px solid var(--im-border); border-radius: 8px; background: var(--im-input-bg); color: var(--im-text); outline: none; }
.pm-search input:focus, .pm-name input:focus { border-color: var(--im-primary); }
.pm-chips { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 16px 0; }
.chip { padding: 2px 8px; border-radius: 10px; background: var(--im-active); color: var(--im-primary); font-size: 12px; cursor: pointer; }
.chip.bot { background: rgba(0, 176, 79, .12); color: #00b04f; }
.pm-body { flex: 1; overflow: auto; padding: 8px 10px 12px; min-height: 160px; }
.pm-sec { margin: 8px 6px 4px; font-size: 12px; color: var(--im-text-2); font-weight: 600; }
.pm-dept { display: flex; align-items: center; gap: 6px; padding: 7px 8px; border-radius: 8px; cursor: pointer; font-size: 13px; color: var(--im-text-2); user-select: none; }
.pm-dept:hover { background: var(--im-hover); }
.pm-dept.group { margin-top: 10px; }
.pm-dept .nm { color: var(--im-text); font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pm-dept.group .nm { font-weight: 600; }
.pm-dept .cnt { font-size: 12px; color: var(--im-text-2); flex: none; }
.arrow { width: 16px; height: 16px; flex: none; display: inline-flex; align-items: center; justify-content: center; color: var(--im-text-2); transition: transform .15s ease; }
.arrow svg { width: 12px; height: 12px; fill: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
.arrow.open { transform: rotate(90deg); }
.pm-item { display: flex; align-items: center; gap: 8px; padding: 5px 6px; border-radius: 6px; cursor: pointer; font-size: 13px; }
.pm-item:hover { background: var(--im-hover); }
.pm-item .off { color: var(--im-text-2); text-decoration: line-through; }
.pm-item .tag { font-size: 11px; color: var(--im-text-2); }
.face { width: 28px; height: 28px; border-radius: 6px; flex: none; object-fit: cover; }
.face.char { background: var(--im-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 13px; }
.face.char.bot { background: #00b04f; }
.pm-empty { color: var(--im-text-2); font-size: 12px; text-align: center; padding: 20px 0; }
.pm-foot { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-top: 1px solid var(--im-border); }
.pm-foot .hint { flex: 1; color: var(--im-text-2); font-size: 12px; }
.pm-foot button { padding: 7px 14px; }
</style>
