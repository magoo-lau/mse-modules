<template>
  <el-tabs v-model="tab" type="border-card" class="tabs">
    <!-- ==================== API 定义 ==================== -->
    <el-tab-pane label="API 管理" name="api">
      <div class="toolbar">
        <el-input v-model="apiQuery.keyword" placeholder="名称/路径" clearable style="width: 180px" @keyup.enter="loadApis" />
        <el-button type="primary" @click="loadApis">查询</el-button>
        <el-button v-permission="'data:service:save'" type="primary" plain @click="openCreateApi">新建 API</el-button>
      </div>
      <el-table :data="apis" size="small" v-loading="apiLoading">
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="path" label="路径" min-width="160" show-overflow-tooltip />
        <el-table-column label="数据源" width="100">
          <template #default="{ row }">{{ row.datasourceId === 0 ? '中台贴源层' : '外部源 #' + row.datasourceId }}
          </template>
        </el-table-column>
        <el-table-column label="分页" width="60">
          <template #default="{ row }">{{ row.pageEnabled === 1 ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column prop="maxRows" label="行数上限" width="90" align="right" />
        <el-table-column prop="qpsLimit" label="次/分钟" width="90" align="right" />
        <el-table-column label="状态" width="84">
          <template #default="{ row }">
            <el-tag :type="STATUS_TYPE[row.status] || 'info'" size="small">{{ STATUS_LABEL[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'data:service:list'" link type="primary" size="small" @click="previewApi(row)">
              试运行
            </el-button>
            <el-button v-permission="'data:service:save'" link type="primary" size="small" @click="openEditApi(row)">
              编辑
            </el-button>
            <el-button v-if="[0, 3].includes(row.status)" v-permission="'data:service:save'" link type="warning"
              size="small" @click="submitApi(row)">提交审批</el-button>
            <el-button v-if="row.status === 1" v-permission="'data:service:approve'" link type="success" size="small"
              @click="approveApi(row)">审批</el-button>
            <el-button v-if="row.status === 2" v-permission="'data:service:save'" link size="small"
              @click="offlineApi(row)">下线</el-button>
            <el-button v-permission="'data:service:list'" link size="small" @click="showApprovals(row)">记录</el-button>
            <el-button v-permission="'data:service:delete'" link type="danger" size="small" @click="removeApi(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="apiTotal" v-model:page="apiQuery.pageNum" v-model:size="apiQuery.pageSize" @change="loadApis" />
    </el-tab-pane>

    <!-- ==================== 调用方应用 ==================== -->
    <el-tab-pane label="调用方应用" name="app">
      <div class="toolbar">
        <el-input v-model="appQuery.keyword" placeholder="应用名称" clearable style="width: 180px" @keyup.enter="loadApps" />
        <el-button type="primary" @click="loadApps">查询</el-button>
        <el-button v-permission="'data:service:app'" type="primary" plain @click="openCreateApp">新建应用</el-button>
      </div>
      <el-table :data="apps" size="small" v-loading="appLoading">
        <el-table-column prop="name" label="应用" min-width="140" show-overflow-tooltip />
        <el-table-column prop="appKey" label="AppKey" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'data:service:app'" link type="primary" size="small" @click="showGrants(row)">
              授权({{ row._grantCount || 0 }})
            </el-button>
            <el-button v-permission="'data:service:app'" link type="warning" size="small" @click="resetSecret(row)">
              重置密钥
            </el-button>
            <el-button v-permission="'data:service:app'" link size="small" @click="toggleApp(row)">
              {{ row.status === 0 ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'data:service:app'" link type="danger" size="small" @click="removeApp(row)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="appTotal" v-model:page="appQuery.pageNum" v-model:size="appQuery.pageSize" @change="loadApps" />
    </el-tab-pane>

    <!-- ==================== 调用日志 ==================== -->
    <el-tab-pane label="调用日志" name="log">
      <el-table :data="apiLogs" size="small" v-loading="logLoading">
        <el-table-column prop="apiId" label="API#" width="70" />
        <el-table-column prop="appId" label="应用#" width="70" />
        <el-table-column prop="params" label="参数" min-width="180" show-overflow-tooltip />
        <el-table-column prop="rowCount" label="行数" width="70" align="right" />
        <el-table-column prop="costMs" label="耗时(ms)" width="90" align="right" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'success' ? 'success' : 'danger'" size="small">
              {{ row.status === 'success' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误" min-width="160" show-overflow-tooltip />
        <el-table-column prop="clientIp" label="来源 IP" width="120" />
        <el-table-column prop="createTime" label="时间" width="150">
          <template #default="{ row }">{{ fmt(row.createTime) }}</template>
        </el-table-column>
      </el-table>
      <Pagination :total="logTotal" v-model:page="logQuery.pageNum" v-model:size="logQuery.pageSize" @change="loadLogs" />
    </el-tab-pane>
  </el-tabs>

  <!-- API 编辑 -->
  <el-dialog v-model="apiDialog" :title="apiForm.id ? '编辑 API' : '新建 API'" width="720px">
    <el-form :model="apiForm" label-width="100px">
      <el-form-item label="名称" required>
        <el-input v-model="apiForm.name" />
      </el-form-item>
      <el-form-item label="开放路径" required>
        <el-input v-model="apiForm.path" placeholder="小写字母开头，如 order-daily">
          <template #prepend>/openapi/data/</template>
        </el-input>
      </el-form-item>
      <el-form-item label="数据源" required>
        <el-select v-model="apiForm.datasourceId" style="width: 100%">
          <el-option :value="0" label="中台贴源层（仅 ods_ 表，自动隔离租户）" />
          <el-option v-for="ds in datasources" :key="ds.id" :value="ds.id" :label="`${ds.name}（外部源）`" />
        </el-select>
      </el-form-item>
      <el-form-item label="SQL 模板" required>
        <el-input v-model="apiForm.sqlTemplate" type="textarea" :rows="4"
          placeholder="仅 SELECT，参数用 :name 占位，如：SELECT code, cfg_value FROM ods_sys_config WHERE cfg_key = :key" />
      </el-form-item>
      <el-form-item label="参数定义">
        <el-input v-model="apiForm.paramsJson" type="textarea" :rows="2"
          placeholder='[{"name":"key","type":"string","required":true,"remark":"配置键"}]' />
      </el-form-item>
      <el-form-item label="分页">
        <el-switch v-model="apiForm.pageEnabled" :active-value="1" :inactive-value="0" />
      </el-form-item>
      <el-form-item label="行数上限">
        <el-input-number v-model="apiForm.maxRows" :min="1" :max="100000" />
      </el-form-item>
      <el-form-item label="超时(ms)">
        <el-input-number v-model="apiForm.timeoutMs" :min="1000" :max="60000" :step="1000" />
      </el-form-item>
      <el-form-item label="次/分钟">
        <el-input-number v-model="apiForm.qpsLimit" :min="0" :max="10000" />
        <span class="hint">0 = 跟随全局默认（60）</span>
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="apiForm.remark" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="apiDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveApi">保存</el-button>
    </template>
  </el-dialog>

  <!-- 试运行结果 -->
  <el-dialog v-model="previewDialog" :title="`试运行：${previewApiName}`" width="720px">
    <el-input v-model="previewParams" type="textarea" :rows="2" placeholder='参数 JSON，如 {"key":"mse.name"}' />
    <el-button class="mt8" type="primary" size="small" :loading="previewLoading" @click="runPreview">执行</el-button>
    <template v-if="previewResult">
      <div class="section-title">返回 {{ previewResult.rows.length }} 行{{ previewResult.hasMore ? '（还有更多）' : '' }}，
        耗时 {{ previewResult.costMs }}ms</div>
      <el-table v-if="previewResult.rows.length" :data="previewResult.rows" size="small" max-height="300" border>
        <el-table-column v-for="col in previewResult.columns" :key="col" :prop="col" :label="col" min-width="120"
          show-overflow-tooltip />
      </el-table>
      <el-empty v-else description="无数据返回" :image-size="60" />
    </template>
  </el-dialog>

  <!-- 授权管理 -->
  <el-dialog v-model="grantDialog" :title="`授权管理：${grantApp?.name || ''}`" width="680px">
    <div class="toolbar">
      <el-select v-model="grantForm.apiIds" multiple filterable collapse-tags collapse-tags-tooltip
        placeholder="选择 API（可多选批量授权）" style="flex: 1; min-width: 220px">
        <el-option v-for="a in allApis" :key="a.id" :value="a.id"
          :label="`${a.name}（/openapi/data/${a.path}）${grantedApiIds.has(a.id) ? ' [已授权]' : ''}`" />
      </el-select>
      <el-date-picker v-model="grantForm.expireTime" type="datetime" placeholder="过期时间（留空=永久）"
        value-format="YYYY-MM-DDTHH:mm:ss" :disabled-date="disabledPastDate" style="width: 200px" />
      <el-button v-permission="'data:service:app'" type="primary" size="small" @click="addGrant">授权</el-button>
    </div>
    <el-table :data="grants" size="small">
      <el-table-column prop="apiName" label="API" min-width="130" />
      <el-table-column prop="apiPath" label="路径" min-width="130" />
      <el-table-column label="过期时间" width="180">
        <template #default="{ row }">
          <template v-if="row.expireTime">
            <span>{{ fmt(row.expireTime) }}</span>
            <el-tag v-if="isExpired(row.expireTime)" type="danger" size="small" style="margin-left: 4px">已过期</el-tag>
          </template>
          <span v-else>永久</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-permission="'data:service:app'" link type="primary" size="small" @click="openExpire(row)">
            改期
          </el-button>
          <el-button v-permission="'data:service:app'" link type="danger" size="small" @click="removeGrant(row)">
            收回
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>

  <!-- 授权改期 -->
  <el-dialog v-model="expireDialog" :title="`调整过期时间：${expireRow?.apiName || ''}`" width="420px">
    <el-date-picker v-model="expireValue" type="datetime" placeholder="选择新的过期时间" value-format="YYYY-MM-DDTHH:mm:ss"
      :disabled-date="disabledPastDate" style="width: 100%" />
    <div class="hint" style="margin-left: 0">留空保存 = 恢复为永久有效</div>
    <template #footer>
      <el-button @click="expireDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveExpire">保存</el-button>
    </template>
  </el-dialog>

  <!-- 密钥展示（仅一次） -->
  <el-dialog v-model="secretDialog" title="应用凭据（仅显示一次，请妥善保存）" width="520px">
    <el-alert type="warning" :closable="false" show-icon title="关闭本窗口后密钥不再可见，只能重置。" class="mb8" />
    <el-descriptions :column="1" size="small" border>
      <el-descriptions-item label="AppKey">{{ secretView?.appKey }}</el-descriptions-item>
      <el-descriptions-item label="AppSecret">{{ secretView?.appSecret }}</el-descriptions-item>
    </el-descriptions>
    <div class="section-title">调用示例</div>
    <pre class="code">curl -X POST http://&lt;host&gt;:8092/openapi/data/{{ secretView?.appKey ? '&lt;path&gt;' : '' }} \
  -H "X-App-Key: {{ secretView?.appKey }}" \
  -H "X-Timestamp: $(date +%s)" \
  -H "X-Nonce: random-123" \
  -H "X-Sign: &lt;HMAC-SHA256(secret, appKey\ntimestamp\nnonce)&gt;" \
  -H "Content-Type: application/json" \
  -d '{"params":{},"pageNum":1,"pageSize":20}'</pre>
  </el-dialog>

  <!-- API 审批 -->
  <el-dialog v-model="approveDialog" :title="`审批：${approveTarget?.name || ''}`" width="480px">
    <el-form label-width="80px">
      <el-form-item label="结论">
        <el-radio-group v-model="approvePass">
          <el-radio :value="true">通过并发布</el-radio>
          <el-radio :value="false">驳回</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="意见">
        <el-input v-model="approveComment" type="textarea" :rows="2" placeholder="（可选）审批意见" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="approveDialog = false">取消</el-button>
      <el-button type="primary" @click="doApprove">确定</el-button>
    </template>
  </el-dialog>

  <!-- 审批记录 -->
  <el-dialog v-model="approvalDialog" title="审批记录" width="640px">
    <el-table :data="approvals" size="small">
      <el-table-column label="动作" width="90">
        <template #default="{ row }">
          {{ { submit: '提交', approve: '通过', reject: '驳回', offline: '下线' }[row.action] || row.action }}
        </template>
      </el-table-column>
      <el-table-column prop="comment" label="意见" min-width="160" show-overflow-tooltip />
      <el-table-column prop="operatorName" label="操作人" width="100" />
      <el-table-column prop="createTime" label="时间" width="150">
        <template #default="{ row }">{{ fmt(row.createTime) }}</template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { Pagination } from 'mse-ui-shared/pc'

const tab = ref('api')

// ---------- API ----------
const apis = ref<any[]>([])
const apiTotal = ref(0)
const apiLoading = ref(false)
const apiQuery = reactive({ pageNum: 1, pageSize: 10, keyword: '' })
const apiDialog = ref(false)
const saving = ref(false)
const datasources = ref<any[]>([])
const emptyApiForm = {
  id: 0, name: '', path: '', datasourceId: 0, sqlTemplate: '', paramsJson: '',
  pageEnabled: 1, maxRows: 1000, timeoutMs: 10000, qpsLimit: 0, remark: ''
}
const apiForm = reactive<any>({ ...emptyApiForm })

// ---------- 试运行 ----------
const previewDialog = ref(false)
const previewApiName = ref('')
const previewApiId = ref<number | null>(null)
const previewParams = ref('{}')
const previewLoading = ref(false)
const previewResult = ref<any>(null)

// ---------- 应用 ----------
const apps = ref<any[]>([])
const appTotal = ref(0)
const appLoading = ref(false)
const appQuery = reactive({ pageNum: 1, pageSize: 10, keyword: '' })
const grantDialog = ref(false)
const grantApp = ref<any>(null)
const grants = ref<any[]>([])
const grantForm = reactive({ apiIds: [] as number[], expireTime: '' })
const allApis = ref<any[]>([])
const expireDialog = ref(false)
const expireRow = ref<any>(null)
const expireValue = ref<string>('')
const secretDialog = ref(false)
const secretView = ref<any>(null)

// ---------- 日志 ----------
const apiLogs = ref<any[]>([])
const logTotal = ref(0)
const logLoading = ref(false)
const logQuery = reactive({ pageNum: 1, pageSize: 10 })

const fmt = (v: string) => (v ? v.replace('T', ' ').slice(0, 19) : '-')

// API 审批流状态：0 草稿 1 待审批 2 已发布 3 已驳回 4 已下线
const STATUS_LABEL: Record<number, string> = { 0: '草稿', 1: '待审批', 2: '已发布', 3: '已驳回', 4: '已下线' }
const STATUS_TYPE: Record<number, string> = { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger', 4: 'info' }

async function loadApis() {
  apiLoading.value = true
  try {
    const data: any = await request.get('/data/api/page', { params: apiQuery })
    apis.value = data.records || []
    apiTotal.value = Number(data.total)
  } catch {
    /* 拦截器已提示 */
  } finally {
    apiLoading.value = false
  }
}

async function loadDatasources() {
  try {
    const data: any = await request.get('/data/source/page', { params: { pageNum: 1, pageSize: 100 } })
    datasources.value = data.records || []
  } catch {
    /* 拦截器已提示 */
  }
}

function openCreateApi() {
  Object.assign(apiForm, emptyApiForm, { id: 0 })
  apiDialog.value = true
}

function openEditApi(row: any) {
  Object.assign(apiForm, emptyApiForm, row, { id: row.id })
  apiDialog.value = true
}

async function saveApi() {
  saving.value = true
  try {
    const payload = { ...apiForm }
    if (payload.path) payload.path = payload.path.replace(/^\/+/, '')
    if (apiForm.id) {
      await request.put('/data/api', payload)
    } else {
      await request.post('/data/api', payload)
    }
    ElMessage.success('已保存')
    apiDialog.value = false
    loadApis()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function submitApi(row: any) {
  await ElMessageBox.confirm(`提交「${row.name}」进入审批？`, '提交确认')
  try {
    await request.post(`/data/api/${row.id}/submit`)
    ElMessage.success('已提交审批')
    loadApis()
  } catch {
    /* 拦截器已提示 */
  }
}

const approveDialog = ref(false)
const approveTarget = ref<any>(null)
const approvePass = ref(true)
const approveComment = ref('')

function approveApi(row: any) {
  approveTarget.value = row
  approvePass.value = true
  approveComment.value = ''
  approveDialog.value = true
}

async function doApprove() {
  if (!approveTarget.value) return
  try {
    await request.post(`/data/api/${approveTarget.value.id}/approve`, {
      pass: approvePass.value, comment: approveComment.value || null
    })
    ElMessage.success(approvePass.value ? '已通过并发布' : '已驳回')
    approveDialog.value = false
    loadApis()
  } catch {
    /* 拦截器已提示 */
  }
}

async function offlineApi(row: any) {
  const { value } = await ElMessageBox.prompt(`下线「${row.name}」？开放调用将立即拒绝。可填写原因：`, '下线确认',
    { inputPlaceholder: '（可选）下线原因', confirmButtonText: '下线' })
  try {
    await request.post(`/data/api/${row.id}/offline`, { comment: value || null })
    ElMessage.success('已下线')
    loadApis()
  } catch {
    /* 拦截器已提示 */
  }
}

const approvalDialog = ref(false)
const approvals = ref<any[]>([])

async function showApprovals(row: any) {
  try {
    approvals.value = await request.get(`/data/api/${row.id}/approvals`)
    approvalDialog.value = true
  } catch {
    /* 拦截器已提示 */
  }
}

async function removeApi(row: any) {
  await ElMessageBox.confirm(`确认删除 API「${row.name}」？已授权调用方立即失效。`, '删除确认', { type: 'warning' })
  await request.delete(`/data/api/${row.id}`)
  ElMessage.success('已删除')
  loadApis()
}

function previewApi(row: any) {
  previewApiName.value = row.name
  previewApiId.value = row.id
  previewParams.value = '{}'
  previewResult.value = null
  previewDialog.value = true
}

async function runPreview() {
  if (!previewApiId.value) return
  previewLoading.value = true
  try {
    previewResult.value = await request.post(`/data/api/${previewApiId.value}/preview`, {
      paramsJson: previewParams.value
    }).then((r: any) => r.data)
  } catch {
    /* 拦截器已提示 */
  } finally {
    previewLoading.value = false
  }
}

// ---------- 应用 ----------
async function loadApps() {
  appLoading.value = true
  try {
    const data: any = await request.get('/data/apiapp/page', { params: appQuery })
    apps.value = data.records || []
    appTotal.value = Number(data.total)
    loadGrantCounts()
  } catch {
    /* 拦截器已提示 */
  } finally {
    appLoading.value = false
  }
}

async function loadGrantCounts() {
  for (const app of apps.value) {
    try {
      const list: any[] = await request.get(`/data/apiapp/${app.id}/grants`)
      app._grantCount = list.length
    } catch {
      app._grantCount = 0
    }
  }
}

async function openCreateApp() {
  const { value } = await ElMessageBox.prompt('输入应用名称', '新建调用方应用', { inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
  try {
    const view: any = await request.post('/data/apiapp', { name: value })
    secretView.value = view
    secretDialog.value = true
    loadApps()
  } catch {
    /* 拦截器已提示 */
  }
}

async function resetSecret(row: any) {
  await ElMessageBox.confirm(`确认重置「${row.name}」的密钥？旧密钥立即失效。`, '重置确认', { type: 'warning' })
  try {
    const view: any = await request.post(`/data/apiapp/${row.id}/secret/reset`)
    secretView.value = view
    secretDialog.value = true
  } catch {
    /* 拦截器已提示 */
  }
}

async function toggleApp(row: any) {
  await request.post(`/data/apiapp/${row.id}/status`, null, { params: { enable: row.status !== 0 } })
  loadApps()
}

async function removeApp(row: any) {
  await ElMessageBox.confirm(`确认删除应用「${row.name}」？其全部授权一并收回。`, '删除确认', { type: 'warning' })
  await request.delete(`/data/apiapp/${row.id}`)
  ElMessage.success('已删除')
  loadApps()
}

// ---------- 授权 ----------
const grantedApiIds = computed(() => new Set(grants.value.map((g: any) => g.apiId)))
const isExpired = (v: string) => !!v && new Date(v).getTime() < Date.now()
const disabledPastDate = (d: Date) => d.getTime() < Date.now() - 86400000

async function showGrants(row: any) {
  grantApp.value = row
  grantForm.apiIds = []
  grantForm.expireTime = ''
  grantDialog.value = true
  await reloadGrants()
  if (!allApis.value.length) {
    try {
      const data: any = await request.get('/data/api/page', { params: { pageNum: 1, pageSize: 200 } })
      allApis.value = data.records || []
    } catch {
      /* 拦截器已提示 */
    }
  }
}

async function reloadGrants() {
  if (!grantApp.value) return
  try {
    grants.value = await request.get(`/data/apiapp/${grantApp.value.id}/grants`)
  } catch {
    /* 拦截器已提示 */
  }
}

async function addGrant() {
  if (!grantApp.value) return
  if (!grantForm.apiIds.length) {
    ElMessage.warning('请选择要授权的 API')
    return
  }
  try {
    const r: any = await request.post(`/data/apiapp/${grantApp.value.id}/grants`, {
      apiIds: grantForm.apiIds,
      expireTime: grantForm.expireTime || null
    })
    ElMessage.success(r.updated ? `已授权 ${r.added} 个，刷新过期 ${r.updated} 个` : `已授权 ${r.added} 个`)
    grantForm.apiIds = []
    grantForm.expireTime = ''
    reloadGrants()
  } catch {
    /* 拦截器已提示 */
  }
}

function openExpire(row: any) {
  expireRow.value = row
  expireValue.value = row.expireTime ? String(row.expireTime).slice(0, 19) : ''
  expireDialog.value = true
}

async function saveExpire() {
  if (!expireRow.value || !grantApp.value) return
  try {
    await request.post(`/data/apiapp/${grantApp.value.id}/grants`, {
      apiIds: [expireRow.value.apiId],
      expireTime: expireValue.value || null
    })
    ElMessage.success('已更新过期时间')
    expireDialog.value = false
    reloadGrants()
  } catch {
    /* 拦截器已提示 */
  }
}

async function removeGrant(row: any) {
  if (!grantApp.value) return
  await request.delete(`/data/apiapp/${grantApp.value.id}/grants/${row.apiId}`)
  ElMessage.success('已收回')
  reloadGrants()
}

// ---------- 日志 ----------
async function loadLogs() {
  logLoading.value = true
  try {
    const data: any = await request.get('/data/apiapp/logs', { params: logQuery })
    apiLogs.value = data.records || []
    logTotal.value = Number(data.total)
  } catch {
    /* 拦截器已提示 */
  } finally {
    logLoading.value = false
  }
}

onMounted(() => {
  loadApis()
  loadApps()
  loadLogs()
  loadDatasources()
})
</script>

<style scoped>
.tabs {
  min-height: 100%;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.pager {
  margin-top: 8px;
  justify-content: flex-end;
}
.mt8 {
  margin-top: 8px;
}
.mb8 {
  margin-bottom: 8px;
}
.section-title {
  font-weight: 600;
  margin: 12px 0 8px;
}
.hint {
  color: var(--el-text-color-secondary);
  margin-left: 8px;
  font-size: 12px;
}
.code {
  background: var(--el-fill-color-light);
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  overflow: auto;
}
</style>
