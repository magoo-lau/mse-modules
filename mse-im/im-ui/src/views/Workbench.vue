<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>工作台配置（聊天客户端宫格入口）</span>
          <div class="header-actions">
            <el-button size="small" @click="openMenuPicker">从页面菜单添加</el-button>
            <el-button size="small" type="primary" plain @click="openExternalDialog">添加外部应用</el-button>
            <el-button type="primary" size="small" :loading="saving" @click="save">保存</el-button>
          </div>
        </div>
      </template>

      <p class="hint">
        管理聊天客户端「工作台」宫格入口：可启用/停用/删除，图标支持 emoji 选择或上传图片；「分组」为宫格
        分组名（留空时客户端按菜单目录推导分组，外部应用归「外部应用」）；外部应用支持
        免登授权跳转（类似微信接入第三方：跳转 URL 带一次性授权码，对方服务端用 app_key/app_secret
        兑换用户身份）——凭证与域名白名单归口开放平台「应用管理」，入口域名须在应用域名白名单内。
        可配置每个入口默认内嵌打开还是弹窗打开；「可见角色」限制入口仅授权角色可见/可跳转（留空=全员可见，
        页面菜单入口另受本人菜单权限约束）。删除/停用需点击「保存」生效；
        未配置任何入口时，客户端默认展示全部可见页面菜单。
      </p>

      <el-table :data="rows" size="small" v-loading="loading" empty-text="暂无入口——点击上方按钮添加">
        <el-table-column label="预览" width="70" align="center">
          <template #default="{ row }">
            <img v-if="isImgIcon(effIcon(row))" class="preview-img" :src="iconPreviewSrc(effIcon(row))" alt="" />
            <el-icon v-else-if="epIconComp(effIcon(row))" class="preview-ep"><component :is="epIconComp(effIcon(row))" /></el-icon>
            <span v-else class="preview">{{ effIcon(row) || '🧩' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="入口" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="row.entryType === 1" size="small" type="warning" class="row-tag">外链</el-tag>
            <el-tag v-if="row.authMode === 1" size="small" type="success" class="row-tag">
              免登 · {{ appNameOf(row) || '未关联应用' }}
            </el-tag>
            <span>{{ row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column label="名称 / 标题" min-width="160">
          <template #default="{ row }">
            <el-input v-model="row.title" size="small" maxlength="64"
                      :placeholder="row.entryType === 1 ? '必填' : row.menuName || '缺省用菜单名'" />
          </template>
        </el-table-column>
        <el-table-column label="分组" min-width="130">
          <template #default="{ row }">
            <el-input v-model="row.groupName" size="small" maxlength="64" placeholder="缺省按目录推导" />
          </template>
        </el-table-column>
        <el-table-column label="图标" min-width="250">
          <template #default="{ row }">
            <div class="icon-cell">
              <el-input v-model="row.icon" size="small" :placeholder="iconPlaceholder(row)" class="icon-input" />
              <el-popover placement="bottom" :width="264" trigger="click">
                <template #reference>
                  <el-button size="small" title="选择 emoji">😀</el-button>
                </template>
                <div class="emoji-grid">
                  <button v-for="e in EMOJIS" :key="e" class="emoji-item" type="button"
                          @click="pickEmoji(row, e)">{{ e }}</button>
                </div>
                <div class="emoji-clear">
                  <el-button size="small" text type="danger" @click="row.icon = ''">清除图标</el-button>
                </div>
              </el-popover>
              <el-upload :show-file-list="false" :auto-upload="false" accept=".png,.jpg,.jpeg,.gif,.webp"
                         :on-change="(file: any) => pickIconFile(row, file)">
                <el-button size="small" title="上传图片">上传</el-button>
              </el-upload>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="打开方式" width="100">
          <template #default="{ row }">
            <el-select v-model="row.openMode" size="small">
              <el-option label="内嵌" :value="0" />
              <el-option label="弹窗" :value="1" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="可见角色" min-width="190">
          <template #default="{ row }">
            <el-select v-model="row.roleIds" size="small" multiple collapse-tags collapse-tags-tooltip
                       filterable clearable placeholder="全员" style="width: 100%">
              <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="Number(r.id)" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="排序" width="150">
          <template #default="{ $index, row }">
            <el-input-number v-model="row.sort" size="small" :min="0" :max="999" controls-position="right"
                             style="width: 80px" />
            <el-button link size="small" :disabled="$index === 0" title="上移"
                       @click="move($index, -1)">↑</el-button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-model="row.status" :active-value="0" :inactive-value="1"
                       inline-prompt active-text="启用" inactive-text="停用" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row, $index }">
            <el-button link size="small" type="danger" @click="rows.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 从页面菜单添加 -->
    <el-dialog v-model="menuPickerVisible" title="从页面菜单添加入口" width="560px">
      <p class="hint">候选 = 本租户已开通的子应用页面菜单（与聊天客户端同一权限口径），已加入工作台的不再列出。</p>
      <el-table :data="menuCandidates" size="small" max-height="380" empty-text="无可添加的页面菜单"
                @selection-change="(sel: any[]) => menuSelection = sel" ref="menuTableRef">
        <el-table-column type="selection" width="45" />
        <el-table-column label="图标" width="60" align="center">
          <template #default="{ row }">
            <img v-if="isImgIcon(row.icon)" class="preview-img" :src="iconPreviewSrc(row.icon)" alt="" />
            <el-icon v-else-if="epIconComp(row.icon || 'Box')" class="preview-ep"><component :is="epIconComp(row.icon || 'Box')" /></el-icon>
            <span v-else class="preview">{{ row.icon || 'Box' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="菜单名" width="180" />
        <el-table-column prop="path" label="mse 路由" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button size="small" @click="menuPickerVisible = false">取消</el-button>
        <el-button type="primary" size="small" :disabled="!menuSelection.length" @click="confirmMenuAdd">
          添加 {{ menuSelection.length }} 项
        </el-button>
      </template>
    </el-dialog>

    <!-- 添加外部应用 -->
    <el-dialog v-model="externalVisible" title="添加外部应用" width="520px">
      <el-form label-width="90px" size="small">
        <el-form-item label="名称" required>
          <el-input v-model="externalForm.title" maxlength="64" placeholder="宫格展示名，如 客户工单" />
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="externalForm.path" placeholder="外部系统完整地址，如 https://erp.example.com/app" />
        </el-form-item>
        <el-form-item label="分组">
          <el-input v-model="externalForm.groupName" maxlength="64" placeholder="宫格分组名，留空归「外部应用」" />
        </el-form-item>
        <el-form-item label="打开方式">
          <el-radio-group v-model="externalForm.openMode">
            <el-radio :value="0">内嵌</el-radio>
            <el-radio :value="1">弹窗</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="授权方式">
          <el-radio-group v-model="externalForm.authMode">
            <el-radio :value="0">直连</el-radio>
            <el-radio :value="1">免登授权</el-radio>
          </el-radio-group>
          <p class="hint">
            免登授权（微信式）：跳转 URL 带一次性授权码，对方服务端用 app_key/app_secret 调
            POST /im/oauth/token 兑换用户身份（code 5 分钟有效）。凭证在开放平台「应用管理」创建应用时发放。
          </p>
        </el-form-item>
        <el-form-item v-if="externalForm.authMode === 1" label="关联应用" required>
          <el-select v-model="externalForm.openAppId" filterable placeholder="选择本租户的开放平台应用"
                     style="width: 100%">
            <el-option v-for="a in openApps" :key="a.id"
                       :label="a.status === 0 ? `${a.name}（${a.appKey}）` : `${a.name}（${a.appKey}，已停用）`"
                       :value="a.id" :disabled="a.status !== 0" />
          </el-select>
          <p class="hint">
            凭证与域名白名单归口该应用：app_key/app_secret 在应用管理中创建/重置时发放（仅展示一次），
            入口域名须在应用「域名白名单」内，应用停用/删除后免登即时失效。
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="externalVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="confirmExternalAdd">添加（保存后生效）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, type Component } from 'vue'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  getWorkbench, listWorkbenchMenus, listWorkbenchOpenApps, listWorkbenchRoles, saveWorkbench, uploadWorkbenchIcon,
  type WorkbenchItem, type OpenAppOption, type RoleOption,
} from '../api/im'

/** Element Plus 图标名 → 组件（与基座 sys_menu.icon 同体系，按名动态渲染）；非图标名返回 undefined */
const EP_ICONS = ElementPlusIconsVue as Record<string, Component>
function epIconComp(name?: string) {
  const n = (name || '').trim()
  return n && EP_ICONS[n] ? EP_ICONS[n] : undefined
}

/** 表格行（entryType 0=mse 菜单 1=外部应用；status 0=启用 1=停用；roleIds 空=全员可见；
 *  groupName 空=客户端按菜单树目录推导分组） */
interface Row {
  id?: number | string
  entryType: number
  path: string
  menuName: string
  title: string
  icon: string
  groupName: string
  openMode: number
  authMode: number
  openAppId?: number | string
  roleIds: number[]
  sort: number
  status: number
}

const EMOJIS = [
  '🧩', '📊', '📈', '📋', '🗂️', '📁', '📊', '🗓️', '✅', '🎯',
  '🏭', '⚙️', '🔧', '🛠️', '📦', '🚚', '🏷️', '💰', '💳', '🧾',
  '👥', '🛡️', '🔍', '📡', '🔔', '💡', '🤖', '🧠', '💬', '📮',
  '🌐', '🔗', '⚙️', '🚀', '⭐', '🔥', '🌱', '🎮', '📱', '💻',
]

const rows = ref<Row[]>([])
const candidates = ref<{ path: string; name: string; icon: string }[]>([])
const openApps = ref<OpenAppOption[]>([])
const roles = ref<RoleOption[]>([])
const loading = ref(false)
const saving = ref(false)

/** 菜单 path → icon（未配图标的不入表）：预览/占位提示用，行 icon 留空即跟随菜单图标 */
const menuIconByPath = ref(new Map<string, string>())

const menuPickerVisible = ref(false)
const menuSelection = ref<{ path: string; name: string; icon: string }[]>([])
const externalVisible = ref(false)
const externalForm = reactive<{ title: string; path: string; groupName: string; openMode: number; authMode: number; openAppId?: number | string }>({
  title: '', path: '', groupName: '', openMode: 0, authMode: 0,
})

/** 菜单候选（排除已加入工作台的路径） */
const menuCandidates = computed(() =>
  candidates.value.filter(m => !rows.value.some(r => r.entryType === 0 && r.path === m.path)))

onMounted(() => {
  load()
  loadOpenApps()
  loadRoles()
})

async function loadRoles() {
  try {
    roles.value = (await listWorkbenchRoles()) || []
  } catch {
    // 角色清单加载失败：下拉置空（保存时后端也会校验），不阻塞配置页
    roles.value = []
  }
}

async function loadOpenApps() {
  try {
    openApps.value = (await listWorkbenchOpenApps()) || []
  } catch {
    // 开放平台未接入/加载失败：下拉置空（选择免登授权时后端也会拦截），不阻塞配置页
    openApps.value = []
  }
}

async function load() {
  loading.value = true
  try {
    // 候选 = 本租户 type=3 页面菜单（与聊天客户端 /system/profile/me 同源），实体序列化为驼峰 microName；
    // icon 随行收集：行 icon 留空时聊天客户端即回退渲染菜单图标
    const profile: any = await listWorkbenchMenus()
    const menus: { path: string; name: string; icon: string }[] = []
    const walk = (nodes: any[]) => {
      for (const n of nodes ?? []) {
        if (n.menu?.type === 3 && n.menu?.path) {
          menus.push({ path: `/sub/${n.menu.microName}${n.menu.path}`, name: n.menu.name, icon: n.menu.icon || '' })
        }
        if (n.children?.length) walk(n.children)
      }
    }
    walk(profile?.menus ?? [])
    candidates.value = menus
    menuIconByPath.value = new Map(menus.filter(m => m.icon).map(m => [m.path, m.icon]))
    // 全量配置行（含停用），按后端排序返回
    const cfg = ((await getWorkbench(true)) || {}).items || []
    const byPath = new Map(menus.map(m => [m.path, m]))
    rows.value = cfg.map(c => ({
      id: c.id,
      entryType: c.entryType ?? 0,
      path: c.path,
      menuName: byPath.get(c.path)?.name || '',
      title: c.title || '',
      icon: c.icon || '',
      groupName: c.groupName || '',
      openMode: c.openMode ?? 0,
      authMode: c.authMode ?? 0,
      openAppId: c.openAppId,
      roleIds: parseRoleIds(c.roleIds),
      sort: c.sort ?? 0,
      status: c.status ?? 0,
    }))
  } finally {
    loading.value = false
  }
}

/** 行展示用应用名（下拉候选实时映射；应用已删除时回退编号） */
function appNameOf(row: Row) {
  const app = openApps.value.find(a => String(a.id) === String(row.openAppId))
  return app ? app.name : (row.openAppId != null ? `#${row.openAppId}` : '')
}

/** 后端 role_ids 逗号分隔字符串 → 数字数组（空/脏值归空数组=全员） */
function parseRoleIds(raw: number[] | string | undefined): number[] {
  if (Array.isArray(raw)) return raw.map(Number).filter(n => !Number.isNaN(n))
  return String(raw || '').split(',').map(s => Number(s.trim())).filter(n => !Number.isNaN(n) && n > 0)
}

/** 图标预览：站内相对路径加 /api 前缀（本地兜底素材），绝对地址原样 */
function iconPreviewSrc(icon: string) {
  const u = (icon || '').trim()
  if (!u || /^(https?:|data:|blob:)/i.test(u)) return u
  return /^\/(fs|im|system)\//.test(u) ? '/api' + u : u
}

function isImgIcon(icon: string) { return /^https?:\/\//i.test(icon || '') || (icon || '').startsWith('/') }

/** 行有效图标 = 配置 icon → 菜单 icon → 菜单入口 Box（与基座侧边栏 type=3 缺省同口径）/ 外部应用空，
 *  与聊天客户端 loadWorkbench 同一解析链——预览即客户端宫格与分享卡片所得 */
function effIcon(row: Row) {
  return row.icon || menuIconByPath.value.get(row.path) || (row.entryType === 0 ? 'Box' : '')
}

/** 图标输入占位：菜单入口留空即跟随菜单图标（有图标名提示具体值，缺省 Box）；外部应用维持原提示 */
function iconPlaceholder(row: Row) {
  if (row.entryType === 1) return 'emoji 或图片 URL'
  const mi = menuIconByPath.value.get(row.path)
  return mi ? `留空用菜单图标 ${mi}` : '留空用菜单图标（缺省 Box）'
}

function pickEmoji(row: Row, emoji: string) { row.icon = emoji }

/** 上传图标：≤2MB、png/jpg/jpeg/gif/webp（与后端白名单同口径），成功后回填站内地址 */
async function pickIconFile(row: Row, file: any) {
  const raw = file?.raw as File | undefined
  if (!raw) return
  if (raw.size > 2 * 1024 * 1024) {
    ElMessage.error('图标不能超过 2MB')
    return
  }
  try {
    row.icon = await uploadWorkbenchIcon(raw)
    ElMessage.success('图标已上传，保存后生效')
  } catch { /* request.ts 已提示 */ }
}

function openMenuPicker() {
  menuSelection.value = []
  menuPickerVisible.value = true
}

function confirmMenuAdd() {
  for (const m of menuSelection.value) {
    rows.value.push({
      entryType: 0, path: m.path, menuName: m.name, title: '', icon: '', groupName: '',
      openMode: 0, authMode: 0, roleIds: [], sort: rows.value.length, status: 0,
    })
  }
  ElMessage.success(`已添加 ${menuSelection.value.length} 个菜单入口，保存后生效`)
  menuPickerVisible.value = false
}

function openExternalDialog() {
  externalForm.title = ''
  externalForm.path = ''
  externalForm.groupName = ''
  externalForm.openMode = 0
  externalForm.authMode = 0
  externalForm.openAppId = undefined
  externalVisible.value = true
  // 打开即拉取应用清单：保证「关联应用」下拉候选就绪
  loadOpenApps()
}

function confirmExternalAdd() {
  const path = externalForm.path.trim()
  if (!externalForm.title.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  if (!/^https?:\/\/\S+$/.test(path)) {
    ElMessage.warning('地址必须为 http(s) 绝对地址')
    return
  }
  if (externalForm.authMode === 1 && !externalForm.openAppId) {
    ElMessage.warning('免登授权必须选择关联的开放平台应用（在「应用管理」创建）')
    return
  }
  rows.value.push({
    entryType: 1, path, menuName: '', title: externalForm.title.trim(), icon: '',
    groupName: externalForm.groupName.trim(),
    openMode: externalForm.openMode, authMode: externalForm.authMode,
    openAppId: externalForm.openAppId, roleIds: [],
    sort: rows.value.length, status: 0,
  })
  ElMessage.success('已添加外部应用，保存后生效')
  externalVisible.value = false
}

/** 上移即调整行序并把排序号重编为视觉顺序（保存按行内排序号生效） */
function move(index: number, delta: number) {
  const list = rows.value
  const target = index + delta
  ;[list[index], list[target]] = [list[target], list[index]]
  list.forEach((r, i) => { r.sort = i })
}

async function save() {
  for (const r of rows.value) {
    if (r.entryType === 1 && !r.title.trim()) {
      ElMessage.warning(`外部应用「${r.path}」必须填写名称`)
      return
    }
    if (r.entryType === 1 && r.authMode === 1 && !r.openAppId) {
      ElMessage.warning(`免登授权入口「${r.path}」必须选择关联的开放平台应用`)
      return
    }
  }
  const items: WorkbenchItem[] = rows.value.map(r => ({
    id: r.id,
    entryType: r.entryType,
    path: r.path,
    title: r.title.trim() || undefined,
    icon: r.icon.trim() || undefined,
    groupName: r.groupName.trim() || undefined,
    openMode: r.openMode,
    authMode: r.authMode,
    openAppId: r.openAppId,
    roleIds: r.roleIds,
    sort: r.sort,
    status: r.status,
  }))
  saving.value = true
  try {
    await saveWorkbench(items)
    ElMessage.success(`已保存 ${items.length} 个工作台入口（聊天客户端即时生效）`)
    await load()
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.page { padding: 16px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.header-actions { display: flex; gap: 8px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 0 10px; }
.preview { font-size: 22px; }
.preview-ep { font-size: 22px; }
.preview-img { width: 26px; height: 26px; object-fit: contain; vertical-align: middle; }
.row-tag { margin-right: 4px; }
.icon-cell { display: flex; align-items: center; gap: 4px; }
.icon-input { flex: 1; min-width: 0; }
.emoji-grid { display: grid; grid-template-columns: repeat(10, 1fr); gap: 2px; }
.emoji-item {
  border: none; background: transparent; font-size: 18px; padding: 3px; cursor: pointer;
  border-radius: 4px; line-height: 1;
}
.emoji-item:hover { background: var(--el-fill-color-light); }
.emoji-clear { text-align: center; margin-top: 4px; }
</style>
