<template>
  <div class="page">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-select v-model="targetId" placeholder="选择 Redis 实例" style="width: 230px" @change="onTargetChange">
          <el-option v-for="t in targets" :key="t.id" :label="`${t.name}（${t.host}:${t.port ?? 6379}）`" :value="t.id" />
        </el-select>
        <el-select v-model="dbNum" filterable allow-create default-first-option placeholder="db" style="width: 96px" @change="onDbChange">
          <el-option v-for="db in dbOptions" :key="db" :label="`db${db}`" :value="db" />
        </el-select>
        <el-input v-model="pattern" placeholder="键模式，如 mse:* / satoken:*" clearable style="width: 220px" @keyup.enter="search" @clear="search" />
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="refresh">刷新</el-button>
        <span class="muted tip">仅浏览 mon_target 中 type=redis 的监控目标</span>
      </div>

      <template v-if="info">
        <el-row :gutter="8" class="stat-row">
          <el-col :span="4"><div class="stat-card"><div class="stat-num">{{ info.version || '-' }}</div><div class="stat-label">版本</div></div></el-col>
          <el-col :span="4">
            <div class="stat-card">
              <div class="stat-num">{{ info.usedMemoryMb ?? '-' }}<span class="stat-unit">MB</span></div>
              <div class="stat-label">内存{{ info.maxmemoryMb ? ` / ${info.maxmemoryMb}MB` : '' }}（{{ info.memUsagePct != null ? info.memUsagePct + '%' : '未设上限' }}）</div>
            </div>
          </el-col>
          <el-col :span="4"><div class="stat-card ok"><div class="stat-num">{{ info.hitRatePct != null ? info.hitRatePct + '%' : '-' }}</div><div class="stat-label">命中率</div></div></el-col>
          <el-col :span="4"><div class="stat-card"><div class="stat-num">{{ info.connectedClients ?? '-' }}</div><div class="stat-label">连接数</div></div></el-col>
          <el-col :span="4"><div class="stat-card"><div class="stat-num">{{ info.opsPerSec ?? '-' }}</div><div class="stat-label">OPS/秒</div></div></el-col>
          <el-col :span="4"><div class="stat-card"><div class="stat-num">{{ info.dbsize ?? '-' }}</div><div class="stat-label">db{{ dbNum }} 键数量</div></div></el-col>
        </el-row>
        <div v-if="Object.keys(info.keyspace).length" class="keyspace-line muted">
          各库键量：
          <span v-for="(stat, db) in info.keyspace" :key="db" class="keyspace-item">
            {{ db }}: {{ stat.keys }}<template v-if="stat.expires">（{{ stat.expires }} 个带过期）</template>
          </span>
        </div>
      </template>
      <el-skeleton v-else-if="infoLoading" :rows="2" animated style="margin-top: 4px" />
    </el-card>

    <el-alert
      v-if="keySet?.capped"
      type="warning"
      :closable="false"
      show-icon
      class="block"
      :title="`匹配键较多，已仅收集前 ${keySet.total} 个，结果不完整，请缩小匹配模式（如 mse:*）`"
    />

    <div class="browser-split">
      <el-card shadow="never" class="tree-card">
        <template #header>
          <div class="tree-header">
            <span>键树<span v-if="keySet" class="muted">（{{ keySet.total }}）</span></span>
            <el-input v-model="filterText" placeholder="树内筛选" clearable style="width: 130px" />
            <el-button @click="collapseAll">收起</el-button>
          </div>
        </template>
        <div v-loading="loading" class="tree-body">
          <el-empty v-if="!loading && !names.length" description="无匹配键" :image-size="60" />
          <el-tree-v2
            v-else
            :key="treeKey"
            :data="visibleTree"
            :props="TREE_PROPS"
            node-key="id"
            :height="TREE_HEIGHT"
            :default-expanded-keys="defaultExpanded"
            @node-click="onNodeClick"
          >
            <template #default="{ data }">
              <span class="tree-node" :class="{ active: data.leaf && data.keyName === activeKey }" :title="data.leaf ? data.keyName : data.label">
                <span class="tree-label">{{ data.label }}</span>
                <span v-if="!data.leaf" class="tree-count">({{ data.count }})</span>
              </span>
            </template>
          </el-tree-v2>
        </div>
      </el-card>

      <el-card shadow="never" class="detail-card">
        <template #header><span>键详情</span></template>
        <div v-loading="detailLoading" class="detail-body">
          <el-empty v-if="!detail" :description="activeKey ? '键不存在或已过期' : '点击左侧键名查看值'" :image-size="80" />
          <template v-else>
            <el-descriptions :column="2" size="small" border class="detail-meta">
              <el-descriptions-item label="键名" :span="2"><span class="key-name">{{ detail.name }}</span></el-descriptions-item>
              <el-descriptions-item label="类型"><el-tag size="small" :type="TYPE_TAG[detail.type] || 'info'">{{ detail.type }}</el-tag></el-descriptions-item>
              <el-descriptions-item label="TTL">{{ ttlText(detail.ttlMs) }}</el-descriptions-item>
              <el-descriptions-item label="编码">{{ detail.encoding || '-' }}</el-descriptions-item>
              <el-descriptions-item label="内存占用">{{ memText(detail.memoryUsage) }}</el-descriptions-item>
              <el-descriptions-item label="长度">{{ detail.length ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="操作">
                <el-button v-if="manageable" v-permission="'monitor:cache:delete'" text type="danger" size="small" @click="removeKey(detail)">删除该键</el-button>
                <span v-else class="muted">只读</span>
              </el-descriptions-item>
            </el-descriptions>

            <el-alert
              v-if="detail.truncated"
              type="info"
              :closable="false"
              show-icon
              class="block"
              :title="`值过大，仅预览前 ${detail.type === 'string' ? '64K 字符' : '200 条'}，完整数据请直连实例查看`"
            />

            <pre v-if="['json', 'text', 'binary'].includes(detail.valueKind)" class="value-pre">{{ detail.value }}</pre>

            <el-table v-else-if="detail.valueKind === 'map'" :data="mapRows(detail.value)" size="small" class="block">
              <el-table-column prop="field" label="field" min-width="140" show-overflow-tooltip />
              <el-table-column prop="value" label="value" min-width="240" show-overflow-tooltip />
            </el-table>

            <el-table v-else-if="detail.valueKind === 'array'" :data="arrayRows(detail.value)" size="small" class="block">
              <el-table-column type="index" label="#" width="52" />
              <el-table-column prop="value" label="member" min-width="360" show-overflow-tooltip />
            </el-table>

            <el-table v-else-if="detail.valueKind === 'scored'" :data="scoredRows(detail.value)" size="small" class="block">
              <el-table-column type="index" label="#" width="52" />
              <el-table-column prop="value" label="member" min-width="260" show-overflow-tooltip />
              <el-table-column prop="score" label="score" width="110" />
            </el-table>

            <el-table v-else-if="detail.valueKind === 'stream'" :data="streamRows(detail.value)" size="small" class="block">
              <el-table-column prop="id" label="消息 ID" width="170" />
              <el-table-column label="fields" min-width="240" show-overflow-tooltip>
                <template #default="{ row }">{{ row.fields }}</template>
              </el-table-column>
            </el-table>

            <el-empty v-else description="该类型暂不支持值预览" :image-size="60" />
          </template>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteRedisKey, getRedisInfo, getRedisKeyDetail, listRedisKeyNames, listRedisTargets,
  type RedisInfo, type RedisKeyDetail, type RedisKeyNames, type RedisTargetItem
} from '../api/monitor'

const props = defineProps<{ manageable?: boolean }>()

const TYPE_TAG: Record<string, 'info' | 'warning' | 'success' | 'danger'> = {
  string: 'info', hash: 'warning', list: 'success', set: 'warning', zset: 'danger', stream: 'info'
}

/** 键树节点：文件夹按冒号前缀聚合（count=子孙键数），叶子对应完整键名（id 带全名，与同名前缀文件夹并存不撞） */
interface KeyTreeNode {
  id: string
  label: string
  leaf: boolean
  keyName?: string
  count?: number
  children?: KeyTreeNode[]
}

/** 虚拟化树需要固定像素高度 */
const TREE_HEIGHT = 520
const TREE_PROPS = { label: 'label', children: 'children' }

const targets = ref<RedisTargetItem[]>([])
const loadingTargets = ref(true)
const targetId = ref<string | number | undefined>()
const dbNum = ref(0)
const pattern = ref('')
const info = ref<RedisInfo | null>(null)
const infoLoading = ref(false)
const keySet = ref<RedisKeyNames | null>(null)
const loading = ref(false)
const filterText = ref('')
const treeKey = ref(0)
const activeKey = ref('')
const detailLoading = ref(false)
const detail = ref<RedisKeyDetail | null>(null)

const names = computed(() => keySet.value?.names ?? [])

/** 可选 db：默认 0 + INFO keyspace 中实际有键的库（支持手输任意库号） */
const dbOptions = computed<number[]>(() => {
  const set = new Set<number>([0])
  Object.keys(info.value?.keyspace ?? {}).forEach(db => {
    const n = Number(db.replace('db', ''))
    if (!Number.isNaN(n)) set.add(n)
  })
  return [...set].sort((a, b) => a - b)
})

// ---------------- 键树构建（与 RDM 一致：按冒号分层、文件夹带计数） ----------------

const treeData = computed<KeyTreeNode[]>(() => buildTree(keySet.value?.names ?? []))

/** 树内筛选：叶子按完整键名包含匹配，命中的文件夹保留其全部子树 */
const visibleTree = computed<KeyTreeNode[]>(() => {
  const q = filterText.value.trim().toLowerCase()
  if (!q) return treeData.value
  const keep = (nodes: KeyTreeNode[]): KeyTreeNode[] => {
    const out: KeyTreeNode[] = []
    for (const n of nodes) {
      if (n.leaf) {
        if (n.keyName!.toLowerCase().includes(q)) out.push(n)
      } else {
        const children = keep(n.children ?? [])
        if (children.length) out.push({ ...n, children })
        else if (n.label.toLowerCase().includes(q)) out.push({ ...n })
      }
    }
    return out
  }
  return keep(treeData.value)
})

/** 重新挂载时生效：无筛选展开根层文件夹，筛选时展开全部命中路径 */
const defaultExpanded = computed<string[]>(() => {
  const ids: string[] = []
  if (filterText.value.trim()) {
    const walk = (nodes: KeyTreeNode[]) => {
      for (const n of nodes) {
        if (!n.leaf) {
          ids.push(n.id)
          walk(n.children ?? [])
        }
      }
    }
    walk(visibleTree.value)
  } else {
    treeData.value.forEach(n => { if (!n.leaf) ids.push(n.id) })
  }
  return ids
})

watch(filterText, () => { treeKey.value++ })

function buildTree(keyNames: string[]): KeyTreeNode[] {
  const folders = new Map<string, KeyTreeNode>()
  const ensureFolder = (path: string): KeyTreeNode => {
    let node = folders.get(path)
    if (!node) {
      node = { id: `d:${path}`, label: path, leaf: false, count: 0, children: [] }
      folders.set(path, node)
    }
    return node
  }
  const root = ensureFolder('')
  for (const name of keyNames) {
    const segs = name.split(':').filter(s => s.length > 0)
    if (!segs.length) segs.push(name)
    let path = ''
    for (let i = 0; i < segs.length - 1; i++) {
      path = path ? `${path}:${segs[i]}` : segs[i]
      ensureFolder(path)
    }
    const parent = segs.length > 1 ? ensureFolder(segs.slice(0, -1).join(':')) : root
    parent.children!.push({ id: `l:${name}`, label: segs[segs.length - 1], leaf: true, keyName: name })
  }
  // 文件夹按路径挂到父文件夹（路径去掉了空段，前缀即父路径）
  for (const [path, node] of folders) {
    if (path === '') continue
    const idx = path.lastIndexOf(':')
    folders.get(idx < 0 ? '' : path.slice(0, idx))!.children!.push(node)
  }
  const finalize = (node: KeyTreeNode): number => {
    if (node.leaf) return 1
    const kids = node.children!
    kids.sort((a, b) => (a.leaf === b.leaf ? a.label.localeCompare(b.label) : a.leaf ? 1 : -1))
    node.count = kids.reduce((sum, kid) => sum + finalize(kid), 0)
    return node.count
  }
  finalize(root)
  return root.children!
}

function collapseAll() {
  filterText.value = ''
  treeKey.value++
}

// ---------------- 数据加载 ----------------

async function loadTargets() {
  loadingTargets.value = true
  try {
    targets.value = await listRedisTargets()
    if (targets.value.length && targetId.value == null) {
      targetId.value = targets.value[0].id
    }
  } finally {
    loadingTargets.value = false
  }
}

async function loadInfo() {
  if (targetId.value == null) return
  infoLoading.value = true
  try {
    info.value = await getRedisInfo(targetId.value, dbNum.value)
  } catch {
    info.value = null
  } finally {
    infoLoading.value = false
  }
}

async function loadTree() {
  if (targetId.value == null) return
  loading.value = true
  try {
    const result = await listRedisKeyNames(targetId.value, { db: dbNum.value, pattern: pattern.value || undefined })
    keySet.value = result
    filterText.value = ''
    treeKey.value++
    // 刷新后原选中键已不存在（过期/被删）时才清空详情，避免无谓打断查看
    if (activeKey.value && !result.names.includes(activeKey.value)) {
      activeKey.value = ''
      detail.value = null
    }
  } catch {
    keySet.value = null
  } finally {
    loading.value = false
  }
}

function loadAll() {
  loadInfo()
  loadTree()
}

function search() {
  loadTree()
}

function onTargetChange() {
  activeKey.value = ''
  detail.value = null
  loadAll()
}

function onDbChange() {
  dbNum.value = Number(dbNum.value) || 0
  activeKey.value = ''
  detail.value = null
  loadAll()
}

function refresh() {
  loadAll()
}

// ---------------- 键详情 / 删键 ----------------

async function onNodeClick(payload: KeyTreeNode | { data: KeyTreeNode }) {
  const data = (payload as { data?: KeyTreeNode }).data ?? (payload as KeyTreeNode)
  if (!data.leaf || targetId.value == null) return
  activeKey.value = data.keyName!
  detailLoading.value = true
  try {
    detail.value = await getRedisKeyDetail(targetId.value, { db: dbNum.value, name: data.keyName! })
  } catch {
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function removeKey(row: { name: string }) {
  if (targetId.value == null) return
  await ElMessageBox.confirm(
    `确认删除键「${row.name}」？该操作对 Redis 实例立即生效且不可恢复。`,
    '删除确认',
    { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
  )
  const deleted = await deleteRedisKey(targetId.value, { db: dbNum.value, name: row.name })
  ElMessage.success(deleted ? '已删除' : '键不存在（可能已过期），无需删除')
  activeKey.value = ''
  detail.value = null
  loadTree()
  loadInfo()
}

const ttlText = (ms: number) => {
  if (ms === -1) return '永久'
  if (ms < 0) return '-'
  const s = ms / 1000
  if (s < 60) return `${Math.round(s)}秒`
  if (s < 3600) return `${Math.round(s / 60)}分钟`
  if (s < 86400) return `${(s / 3600).toFixed(1)}小时`
  return `${(s / 86400).toFixed(1)}天`
}
const memText = (bytes?: number | null) => {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1048576).toFixed(2)} MB`
}
const mapRows = (v: unknown) => Object.entries((v ?? {}) as Record<string, string>).map(([field, value]) => ({ field, value }))
const arrayRows = (v: unknown) => (Array.isArray(v) ? v : []).map(value => ({ value }))
const scoredRows = (v: unknown) =>
  (Array.isArray(v) ? v : []).map(e => ({ value: String((e as Record<string, unknown>).value), score: (e as Record<string, unknown>).score }))
const streamRows = (v: unknown) =>
  (Array.isArray(v) ? v : []).map(e => ({
    id: String((e as Record<string, unknown>).id),
    fields: JSON.stringify((e as Record<string, unknown>).fields ?? {})
  }))

onMounted(async () => {
  await loadTargets()
  if (targetId.value != null) loadAll()
})
</script>

<style scoped>
.page {
  padding: 12px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.tip {
  font-size: 12px;
}
.stat-row {
  margin-top: 12px;
}
.stat-card {
  background: var(--el-bg-color-page);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px 14px;
  text-align: center;
}
.stat-num {
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.stat-unit {
  font-size: 12px;
  font-weight: 400;
  margin-left: 2px;
  color: var(--el-text-color-secondary);
}
.stat-card.ok .stat-num {
  color: var(--el-color-success);
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.keyspace-line {
  margin-top: 10px;
  font-size: 12px;
}
.keyspace-item {
  margin-right: 12px;
}
.block {
  margin-top: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.browser-split {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}
.tree-card {
  width: 400px;
  flex-shrink: 0;
}
.detail-card {
  flex: 1;
  min-width: 0;
}
.tree-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.tree-body {
  min-height: 120px;
}
.detail-body {
  min-height: 200px;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
}
.tree-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tree-count {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tree-node.active .tree-label {
  color: var(--el-color-primary);
  font-weight: 600;
}
.detail-meta {
  margin-bottom: 12px;
}
.key-name {
  word-break: break-all;
}
.value-pre {
  margin: 0;
  padding: 10px 12px;
  background: var(--el-bg-color-page);
  border: 1px solid var(--el-border-color-light);
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 60vh;
  overflow: auto;
  color: var(--el-text-color-regular);
}
</style>
