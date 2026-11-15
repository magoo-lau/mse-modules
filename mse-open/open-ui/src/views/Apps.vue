<template>
  <div class="page">
    <el-card shadow="never">
      <div class="table-toolbar">
        <div class="left">
          <el-button v-permission="'open:app:create'" type="primary" icon="Plus" @click="openCreate">接入应用</el-button>
          <el-button icon="Refresh" @click="load">刷新</el-button>
        </div>
        <div class="filters">
          <el-input v-model="query.keyword" placeholder="名称 / AppKey" clearable style="width: 180px" @keyup.enter="load" />
          <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px">
            <el-option label="启用" :value="0" />
            <el-option label="停用" :value="1" />
          </el-select>
          <el-button type="primary" icon="Search" @click="load">查询</el-button>
        </div>
      </div>

      <el-table :data="rows" border>
        <el-table-column prop="name" label="应用名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="appKey" label="AppKey" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.appKey }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="auth.platform" label="绑定租户" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="Number(row.tenantId) === 0" size="small" type="warning">平台域</el-tag>
            <span v-else>{{ tenantName(row.tenantId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="限流 QPS" width="90" align="center">
          <template #default="{ row }">{{ row.rateLimitQps ?? '默认' }}</template>
        </el-table-column>
        <el-table-column prop="expireTime" label="凭证过期" width="160">
          <template #default="{ row }">{{ row.expireTime || '长期' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-switch v-permission="'open:app:change-status'" :model-value="row.status === 0"
                       @change="changeStatus(row, $event)" />
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'open:app:update'" link type="primary" icon="EditPen" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'open:app:grant'" link type="primary" icon="Key" @click="openGrant(row)">授权</el-button>
            <el-button v-permission="'open:app:reset-secret'" link type="warning" @click="doResetSecret(row)">重置密钥</el-button>
            <el-button v-permission="'open:app:delete'" link type="danger" icon="Delete" @click="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
    </el-card>

    <!-- 新建/编辑 -->
    <el-dialog v-model="dialog" :title="form.id ? '编辑应用' : '接入应用'" width="560px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="应用名称" required>
          <el-input v-model="form.name" placeholder="如：OA 协同办公系统" />
        </el-form-item>
        <el-form-item v-if="auth.platform" label="绑定租户" required>
          <el-select v-model="form.tenantId" filterable style="width: 100%">
            <el-option label="平台域（可访问全部租户数据）" value="0" />
            <el-option v-for="t in tenantOptions" :key="t.id"
                       :label="t.status === 0 ? `${t.name}（${t.code}）` : `${t.name}（${t.code}，已停用）`"
                       :value="String(t.id)" :disabled="t.status !== 0" />
          </el-select>
        </el-form-item>
        <el-form-item label="限流 QPS">
          <el-input-number v-model="form.rateLimitQps" :min="0" controls-position="right"
                           placeholder="留空用平台默认" style="width: 100%" />
        </el-form-item>
        <el-form-item label="凭证过期">
          <el-date-picker v-model="form.expireTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="留空 = 长期有效" style="width: 100%" />
        </el-form-item>
        <el-form-item label="请求签名">
          <el-switch v-model="form.signRequired" :active-value="1" :inactive-value="0" active-text="强制验签" />
          <span class="hint">HMAC-SHA256 + 时间戳防重放 + nonce，算法见开放指南</span>
        </el-form-item>
        <el-form-item label="IP 白名单">
          <el-input v-model="form.ipWhitelist" type="textarea" :rows="2"
                    placeholder="逗号分隔，支持 IPv4 CIDR（如 10.0.0.0/8）；留空 = 不限制" />
        </el-form-item>
        <el-form-item label="域名白名单">
          <el-input v-model="form.domainWhitelist" type="textarea" :rows="2"
                    placeholder="逗号分隔，裸域名或 *.example.com 子域通配（如 example.com, *.api.example.com）；留空 = 不限制" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" @click="doSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 凭证一次性展示 -->
    <el-dialog v-model="credentialDialog" title="应用凭证（仅本次展示，请立即保存）" width="560px"
               :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon class="credential-alert"
                title="AppSecret 采用 AES-GCM 加密存储，关闭本窗口后无法再次查看；泄露请立即重置密钥。" />
      <el-form label-width="90px">
        <el-form-item label="AppKey">
          <el-input :model-value="credential.appKey" readonly class="mono">
            <template #append>
              <el-button icon="CopyDocument" @click="copy(credential.appKey)">复制</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="AppSecret">
          <el-input :model-value="credential.appSecret" readonly class="mono">
            <template #append>
              <el-button icon="CopyDocument" @click="copy(credential.appSecret)">复制</el-button>
            </template>
          </el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button type="primary" @click="credentialDialog = false">我已保存</el-button>
      </template>
    </el-dialog>

    <!-- API 授权：左侧分类（超高滚动）+ 右侧接口表 -->
    <el-dialog v-model="grantDialog" :title="`API 授权 - ${grantApp?.name || ''}`" width="840px">
      <div class="grant-layout">
        <div class="grant-cats">
          <div v-for="c in grantCategories" :key="c.value" class="grant-cat"
               :class="{ active: grantCategory === c.value }" @click="grantCategory = c.value">
            <span>{{ c.name }}</span>
            <span class="grant-cat-count">{{ c.count }}</span>
          </div>
        </div>
        <div class="grant-main">
          <div class="grant-toolbar">
            <span class="grant-summary">已选 {{ grantIds.length }} 项</span>
          </div>
          <!-- row-key + reserve-selection：切换左侧分类时保留已勾选行，勾选状态跨分类累计 -->
          <el-table ref="grantTable" :data="grantFilteredApis" border max-height="420" row-key="id"
                    @selection-change="(rows: any[]) => (grantIds = rows.map((r) => r.id))">
            <el-table-column type="selection" width="45" reserve-selection />
            <el-table-column prop="code" label="API 标识" min-width="180" class-name="mono" />
            <el-table-column prop="name" label="名称" min-width="120" />
            <el-table-column prop="httpMethod" label="方法" width="80" align="center" />
            <el-table-column prop="path" label="路径" min-width="200" class-name="mono" show-overflow-tooltip />
          </el-table>
        </div>
      </div>
      <template #footer>
        <el-button @click="grantDialog = false">取消</el-button>
        <el-button type="primary" @click="doGrant">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { useAuthStore } from '../stores/auth'
import { Pagination } from 'mse-ui-shared/pc'

const auth = useAuthStore()
const rows = ref<any[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', status: undefined as number | undefined })

const dialog = ref(false)
const form = reactive<any>(initForm())
const tenantOptions = ref<any[]>([])

const credentialDialog = ref(false)
const credential = reactive({ appKey: '', appSecret: '' })

const grantDialog = ref(false)
const grantApp = ref<any>(null)
const grantTable = ref()
const apis = ref<any[]>([])
const grantIds = ref<number[]>([])
const grantCategory = ref('')

/** API 标识固定为 open:<biz>:<resource>（后端正则强约束），取中段作分类，如 open:ai:chat → ai */
function apiCategory(code: string) {
  return code?.split(':')[1] || '其他'
}

const grantCategories = computed(() => {
  const counts = new Map<string, number>()
  apis.value.forEach((api) => {
    const name = apiCategory(api.code)
    counts.set(name, (counts.get(name) || 0) + 1)
  })
  const cats = [...counts.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([name, count]) => ({ name, value: name, count }))
  // 「全部」置顶，value 为空串表示不过滤
  return [{ name: '全部', value: '', count: apis.value.length }, ...cats]
})

const grantFilteredApis = computed(() =>
  grantCategory.value ? apis.value.filter((api) => apiCategory(api.code) === grantCategory.value) : apis.value
)

function initForm() {
  // 后端 Jackson 把 Long 统一序列化为字符串（防 JS 精度丢失），tenantId 全程按字符串回显/提交；
  // 归属租户默认当前登录租户（租户态界面隐藏选择、后端同样按会话锁定，仅平台态可选平台域/其他租户）
  return { id: null, name: '', tenantId: String(auth.tenantId ?? '0'), rateLimitQps: undefined, expireTime: '', remark: '', signRequired: 0, ipWhitelist: '', domainWhitelist: '' }
}

async function load() {
  const page = await request.get('/openapp/app/page', { params: { ...query } })
  rows.value = page.records
  total.value = Number(page.total ?? 0)
}

async function loadTenantOptions() {
  try {
    tenantOptions.value = await request.get('/openapp/app/tenant-options')
  } catch {
    // 拦截器已提示；置空仅影响下拉选项与租户名回显（回退显示 ID），不阻塞页面
    tenantOptions.value = []
  }
}

/** 列表里把租户 ID 映射成名称；选项缺失时回退显示 ID（Long 经 Jackson 序列化为字符串，统一按字符串比对） */
function tenantName(tenantId: any) {
  const t = tenantOptions.value.find((o: any) => String(o.id) === String(tenantId))
  return t ? t.name : tenantId
}

async function openCreate() {
  Object.assign(form, initForm())
  dialog.value = true
  await loadTenantOptions()
}

async function openEdit(row: any) {
  Object.assign(form, initForm())
  form.id = row.id
  form.name = row.name
  form.tenantId = String(row.tenantId ?? '0')
  form.rateLimitQps = row.rateLimitQps ?? undefined
  form.expireTime = row.expireTime || ''
  form.signRequired = row.signRequired ?? 0
  form.ipWhitelist = row.ipWhitelist || ''
  form.domainWhitelist = row.domainWhitelist || ''
  form.remark = row.remark || ''
  dialog.value = true
  // 先开弹窗再等选项：选项就位后 el-select 自动把裸 ID 回显成租户名
  await loadTenantOptions()
}

async function doSubmit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写应用名称')
    return
  }
  if (form.id) {
    await request.put('/openapp/app', form)
    ElMessage.success('保存成功')
  } else {
    const created = await request.post('/openapp/app', form)
    credential.appKey = created.appKey
    credential.appSecret = created.appSecret
    credentialDialog.value = true
  }
  dialog.value = false
  load()
}

async function changeStatus(row: any, enable: any) {
  await request.put(`/openapp/app/${row.id}/status`, null, { params: { enable: !!enable } })
  load()
}

async function doResetSecret(row: any) {
  await ElMessageBox.confirm(`确定重置「${row.name}」的 AppSecret？原凭证立即失效。`, '提示', { type: 'warning' })
  const reset = await request.put(`/openapp/app/${row.id}/secret`)
  credential.appKey = row.appKey
  credential.appSecret = reset.appSecret
  credentialDialog.value = true
}

async function doDelete(row: any) {
  await ElMessageBox.confirm(`确定删除应用「${row.name}」？其授权关系与有效凭证将一并失效。`, '提示', { type: 'warning' })
  await request.delete(`/openapp/app/${row.id}`)
  ElMessage.success('删除成功')
  load()
}

async function openGrant(row: any) {
  grantApp.value = row
  const [apiList, grantedIds] = await Promise.all([
    request.get('/openapp/api/list'),
    request.get(`/openapp/app/${row.id}/api-ids`)
  ])
  apis.value = apiList
  grantCategory.value = ''
  grantIds.value = grantedIds
  grantDialog.value = true
  // 等表格渲染后回显已授权行；reserve-selection 会跨数据变化保留勾选，先清掉上一次的残留
  nextTick(() => {
    const table = (grantTable as any).value
    table?.clearSelection()
    apiList.forEach((api: any) => {
      if (grantedIds.includes(api.id)) table?.toggleRowSelection(api, true)
    })
  })
}

async function doGrant() {
  await request.put(`/openapp/app/${grantApp.value.id}/api-ids`, { apiIds: grantIds.value })
  ElMessage.success('授权已更新')
  grantDialog.value = false
}

async function copy(text: string) {
  // navigator.clipboard 仅在安全上下文（https/localhost）可用，经 IP 的 http 环境是 undefined；
  // 统一退回隐藏 textarea + execCommand 的同步复制兜底
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      legacyCopy(text)
    }
    ElMessage.success('已复制')
  } catch {
    legacyCopy(text)
    ElMessage.success('已复制')
  }
}

function legacyCopy(text: string) {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
}

onMounted(() => {
  load()
  // 列表「绑定租户」列需要把 ID 映射成名称，进页面就拉取选项
  loadTenantOptions()
})
</script>

<style scoped>
.table-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.filters {
  display: flex;
  gap: 8px;
  align-items: center;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.mono {
  font-family: Consolas, Monaco, monospace;
}
.credential-alert {
  margin-bottom: 12px;
}
.grant-layout {
  display: flex;
  gap: 12px;
}
/* 左侧分类列表：与右侧表格区同高，超出滚动 */
.grant-cats {
  width: 140px;
  flex-shrink: 0;
  max-height: 452px;
  overflow-y: auto;
  padding-right: 4px;
  border-right: 1px solid var(--el-border-color-lighter);
}
.grant-cat {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 30px;
  padding: 0 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.grant-cat + .grant-cat {
  margin-top: 2px;
}
.grant-cat:hover {
  background: var(--el-fill-color-light);
}
.grant-cat.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.grant-cat-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.grant-cat.active .grant-cat-count {
  color: var(--el-color-primary);
}
.grant-main {
  flex: 1;
  min-width: 0;
}
.grant-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}
.grant-summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
