<template>
  <div class="page">
    <el-tabs v-model="tab">
      <el-tab-pane label="工具登记" name="tools" />
      <el-tab-pane v-if="authStore.platform" label="调用审计" name="logs" />
    </el-tabs>
    <el-alert type="info" :closable="false" show-icon class="intro">
      <template #title>
        <template v-if="authStore.platform">
          工具是智能体的「手脚」：在智能体上勾选绑定后由模型按需调用（画布编排经「工具」节点引用），每次调用落审计。
          内置/本地系统工具启动时自动登记、RPC 工具由业务服务自动推送，均无需手动维护；本页支持启停、测试与手工登记
          HTTP / Dify 外部工具；MCP 外部工具经 MCP 服务接入后在本页只读展示（可测试）。
        </template>
        <template v-else>
          工具是智能体的「手脚」：在智能体上勾选绑定后由模型按需调用（画布编排经「工具」节点引用）。
          系统工具统一登记维护（只读，可查看与测试）；本租户可登记 HTTP / Dify 外部工具，仅本租户可见可用；
          MCP 外部工具（若接入）只读展示，清单与「绑定工具」下拉一致。
        </template>
      </template>
    </el-alert>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="编码/名称" style="width: 200px" clearable @keyup.enter="search" />
      <el-button type="primary" @click="search">查询</el-button>
      <el-button v-permission="'ai:tool:manage'" type="primary" @click="openEdit()">登记工具</el-button>
    </div>
    <el-table :data="rows" border size="small">
      <el-table-column prop="toolCode" label="编码" min-width="140" />
      <el-table-column label="名称" width="150">
        <template #default="{ row }">
          {{ row.name }}
          <el-tag v-if="!authStore.platform && row.source !== 'mcp'" size="small" effect="plain" :type="isOwn(row) ? 'success' : 'info'">
            {{ isOwn(row) ? '自有' : '系统' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="sourceType(row.source)">{{ sourceText(row.source) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提供方/目标" width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ targetText(row) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.source !== 'mcp'" :type="row.status === 0 ? 'success' : 'info'" size="small">
            {{ row.status === 0 ? '启用' : '停用' }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEdit(row)" v-permission="'ai:tool:manage'" link type="primary"
            @click="editTool(row)">编辑</el-button>
          <el-button v-permission="'ai:tool:manage'" link type="primary" @click="testTool(row)">测试</el-button>
          <el-button v-if="canEdit(row)" v-permission="'ai:tool:manage'" link type="danger"
            @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="total" v-model:page="pageNum" v-model:size="pageSize" @change="load" />

    <div v-if="tab === 'logs'" class="toolbar" style="margin-top: 12px">
      <el-input v-model="logFilterTool" placeholder="工具编码" style="width: 160px" clearable @keyup.enter="searchLogs" />
      <el-input v-model="logFilterAgent" placeholder="智能体编码" style="width: 160px" clearable @keyup.enter="searchLogs" />
      <el-input v-model="logFilterSession" placeholder="会话号" style="width: 160px" clearable @keyup.enter="searchLogs" />
      <el-button type="primary" @click="searchLogs">查询</el-button>
    </div>
    <el-table v-if="tab === 'logs'" :data="logs" border size="small" style="margin-top: 12px">
      <el-table-column label="工具" width="160">
        <template #default="{ row }">{{ toolName(row.toolCode) }}</template>
      </el-table-column>
      <el-table-column prop="agentCode" label="智能体" width="120" />
      <el-table-column prop="sessionId" label="会话" width="120" show-overflow-tooltip />
      <el-table-column prop="arguments" label="入参" min-width="160" show-overflow-tooltip />
      <el-table-column prop="result" label="出参" min-width="160" show-overflow-tooltip />
      <el-table-column label="结果" width="70">
        <template #default="{ row }">
          <el-tag :type="row.success === 0 ? 'success' : 'danger'" size="small">
            {{ row.success === 0 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="durationMs" label="耗时(ms)" width="90" />
    </el-table>
    <Pagination v-if="tab === 'logs'" :total="logsTotal" v-model:page="logsPageNum" v-model:size="logsPageSize" @change="loadLogs" />

    <el-dialog v-model="editVisible" :title="form.id ? '编辑工具' : '登记工具'" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="工具来源" required>
          <template v-if="schemaReadonly">
            <el-tag size="small" :type="sourceType(form.source)">{{ sourceText(form.source) }}</el-tag>
            <span v-if="isSystem" class="source-hint">系统工具（启动时自动登记，描述字段以提供方为准）</span>
            <span v-else class="source-hint">RPC 工具（内容以提供方推送为准自动同步，仅可启停）</span>
          </template>
          <el-radio-group v-else v-model="form.source">
            <el-radio v-if="authStore.platform" value="rpc">RPC 远程</el-radio>
            <el-radio value="http">HTTP 接口</el-radio>
            <el-radio value="dify">Dify 应用</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.source === 'rpc'">
          <el-form-item label="提供方服务" required>
            <el-select v-model="form.rpcGroup" filterable allow-create default-first-option style="width: 100%"
              placeholder="spring.application.name，如 mse-oa">
              <el-option v-for="g in rpcGroupOptions" :key="g" :label="g" :value="g" />
            </el-select>
          </el-form-item>
          <div class="source-tip">{{ form.id
            ? '内容字段以提供方推送为准（自动同步），此处仅可启停或修正提供方服务名。'
            : '业务服务实现 RemoteAiTool 后会自动推送登记，此处仅用于手工补登；推送后内容以提供方为准。' }}</div>
        </template>

        <template v-if="form.source === 'http'">
          <el-form-item label="接口地址" required>
            <el-input v-model="cfg.url" placeholder="https://api.example.com/v1/weather" />
          </el-form-item>
          <el-form-item label="请求方法">
            <el-select v-model="cfg.method" style="width: 120px">
              <el-option v-for="m in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']" :key="m" :label="m" :value="m" />
            </el-select>
            <span class="source-hint">GET/DELETE 参数拼 query；POST 类参数作 JSON body</span>
          </el-form-item>
          <el-form-item label="请求头">
            <el-input
              v-model="cfg.headers"
              type="textarea"
              :rows="2"
              placeholder='JSON 对象，如 {"Authorization":"Bearer xxx"}'
            />
          </el-form-item>
          <el-form-item label="Body 模板">
            <el-input
              v-model="cfg.bodyTemplate"
              type="textarea"
              :rows="3"
              placeholder='可选，Jinja2 模板（{{ city }}），渲染模型入参；留空则整包 JSON 作 body'
            />
          </el-form-item>
        </template>

        <template v-if="form.source === 'dify'">
          <el-form-item label="服务地址">
            <el-input v-model="cfg.baseUrl" placeholder="默认 https://api.dify.ai" />
          </el-form-item>
          <el-form-item label="应用 API Key" required>
            <el-input v-model="cfg.apiKey" placeholder="app-xxxxxxxx" show-password />
          </el-form-item>
          <el-form-item label="应用类型">
            <el-select v-model="cfg.appType" style="width: 160px">
              <el-option label="Workflow 工作流" value="workflow" />
              <el-option label="Completion 文本生成" value="completion" />
              <el-option label="Chat 对话型" value="chat" />
            </el-select>
            <span class="source-hint">blocking 模式调用；Chat 类型入参中的 query 键映射为对话输入</span>
          </el-form-item>
          <el-form-item label="调用用户">
            <el-input v-model="cfg.user" placeholder="默认 mse-ai" />
          </el-form-item>
        </template>

        <el-form-item v-if="form.source === 'http' || form.source === 'dify'" label="超时(ms)">
          <el-input-number v-model="cfg.timeoutMillis" :min="1000" :max="120000" :step="1000" />
        </el-form-item>

        <el-form-item v-if="!form.id" label="工具编码" required>
          <el-input v-model="form.toolCode" placeholder="唯一编码，智能体绑定引用，如 weather_query" />
        </el-form-item>
        <el-form-item v-else label="工具编码" required>
          <el-input v-model="form.toolCode" disabled placeholder="编码是智能体绑定的主键，编辑态锁定" />
        </el-form-item>
        <el-form-item label="工具名称">
          <el-input v-model="form.name" :disabled="schemaReadonly" />
        </el-form-item>
        <el-form-item label="功能说明" :required="!schemaReadonly">
          <el-input v-model="form.description" type="textarea" :rows="2" :disabled="schemaReadonly"
            placeholder="面向模型：何时该调用本工具（必填）" />
        </el-form-item>
        <el-form-item label="参数 Schema">
          <div class="schema-box">
            <div class="schema-summary">
              <span class="schema-text">{{ schemaSummary }}</span>
              <el-button v-if="!schemaReadonly" link type="primary" @click="schemaDrawerVisible = true">编辑参数</el-button>
            </div>
            <div v-if="!schemaReadonly" class="schema-note">
              每个参数的说明必填：模型依据说明理解参数用途，缺失将无法正确传参
            </div>
            <JsonCodeEditor
              v-model="form.parametersJsonSchema"
              :readonly="schemaReadonly"
              height="220px"
              title="参数 Schema"
              style="margin-top: 4px"
            />
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="0">启用</el-radio>
            <el-radio :value="1">停用</el-radio>
          </el-radio-group>
          <span class="source-hint">停用后智能体解析该工具时直接跳过</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <ToolSchemaDrawer v-model:visible="schemaDrawerVisible" :schema="form.parametersJsonSchema" @apply="applySchema" />
    <ToolTestDialog v-model:visible="testVisible" :tool="testRow" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { toolApi } from '../api/ai'
import type { AiTool } from '../api/ai'
import { useAuthStore } from '../stores/auth'
import ToolSchemaDrawer from '../components/ToolSchemaDrawer.vue'
import ToolTestDialog from '../components/ToolTestDialog.vue'
import JsonCodeEditor from '../components/JsonCodeEditor.vue'
import { Pagination } from 'mse-ui-shared/pc'

const authStore = useAuthStore()
const tab = ref('tools')
const rows = ref<AiTool[]>([])
const logs = ref<any[]>([])
const keyword = ref('')
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const logsTotal = ref(0)
const logsPageNum = ref(1)
const logsPageSize = ref(20)
/** 审计筛选（对话问题现场按智能体/会话定位调用记录） */
const logFilterTool = ref('')
const logFilterAgent = ref('')
const logFilterSession = ref('')
const editVisible = ref(false)
const form = ref<any>({})
const schemaDrawerVisible = ref(false)
const testVisible = ref(false)
const testRow = ref<AiTool | null>(null)
/** 提供方服务下拉候选（rpc 工具已登记的 rpcGroup 去重；懒加载一次） */
const rpcGroupOptions = ref<string[]>([])
const rpcGroupsLoaded = ref(false)

/** 提供方服务候选：拉取工具分页取 source=rpc 的 rpcGroup 去重（失败静默置空，allow-create 仍可手输） */
async function loadRpcGroups() {
  if (rpcGroupsLoaded.value) return
  rpcGroupsLoaded.value = true
  try {
    const body: any = await toolApi.page({ pageNum: 1, pageSize: 200 })
    rpcGroupOptions.value = [...new Set((body?.records ?? [])
      .filter((r: any) => r?.source === 'rpc' && r?.rpcGroup)
      .map((r: any) => r.rpcGroup as string))]
  } catch {
    rpcGroupOptions.value = []
  }
}

/** 外部工具连接配置（source=http/dify 时序列化为 configJson 提交） */
const cfg = reactive<any>({
  url: '',
  method: 'POST',
  headers: '',
  bodyTemplate: '',
  baseUrl: '',
  apiKey: '',
  appType: 'workflow',
  user: '',
  timeoutMillis: undefined
})

/** 系统工具（内置/本地 Bean 自动登记）：内容字段由启动扫描同步，编辑态置灰 */
const isSystem = computed(() => form.value.source === 'builtin' || form.value.source === 'local')

/**
 * Schema 只读口径：内容字段是否由提供方代码权威维护——
 * builtin/local 恒只读；rpc 编辑态只读（提供方推送/启动扫描会覆盖，避免假编辑）；
 * rpc 手工补登（新建）与 http/dify 自建工具可编辑（唯一持久生效的 UI 编辑）
 */
const schemaReadonly = computed(() => isSystem.value || (form.value.source === 'rpc' && !!form.value.id))

/** 本租户自有工具行（tenantId>0 且归属当前租户） */
function isOwn(row: AiTool) {
  return !!row.tenantId && Number(row.tenantId) === Number(authStore.tenantId)
}

/** 可编辑/删除：平台端管理全部平台行；租户端仅本租户自有行（系统工具只读；MCP 动态行无登记行不可编辑） */
function canEdit(row: AiTool) {
  return !!row.id && (authStore.platform || isOwn(row))
}

/** 参数 Schema 摘要：让使用者不读 JSON 也能了解参数构成 */
const schemaSummary = computed(() => {
  try {
    const schema = JSON.parse(form.value.parametersJsonSchema || '{}')
    const props = schema?.properties ?? {}
    const names = Object.keys(props)
    if (!names.length) return '未定义参数'
    const labels: Record<string, string> = {
      string: '字符串', integer: '整数', number: '数字', boolean: '布尔', array: '数组', object: '对象'
    }
    return `${names.length} 个参数：` + names.map((n) => {
      const p: any = props[n] || {}
      return `${n}（${Array.isArray(p.enum) ? '枚举' : labels[p.type] || p.type || '未知'}）`
    }).join('、')
  } catch {
    return 'Schema JSON 格式有误，请在下方源码中修正'
  }
})

function applySchema(schemaJson: string) {
  form.value.parametersJsonSchema = schemaJson
}

onMounted(load)

async function load() {
  const body: any = await toolApi.page({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value })
  rows.value = [...(body?.records ?? []), ...(await loadMcpRows())]
  total.value = Number(body?.total ?? 0)
  if (authStore.platform) {
    await loadLogs()
  }
}

/**
 * MCP 外部工具只读行：不落 ai_tool 登记表（经 MCP 服务动态接入），取与「绑定工具」
 * 下拉同源的 options 清单 mcp 条目，追加在登记行之后展示——id 置空即不可编辑/删除，仅可测试
 */
async function loadMcpRows(): Promise<AiTool[]> {
  try {
    const opts: any[] = (await toolApi.options()) ?? []
    const kw = keyword.value.trim().toLowerCase()
    return opts
      .filter((o) => o?.source === 'mcp')
      .filter((o) => !kw || o.toolCode.toLowerCase().includes(kw) || (o.name || '').toLowerCase().includes(kw))
      .map((o) => ({
        id: '',
        toolCode: o.toolCode,
        name: o.name,
        description: o.description,
        parametersJsonSchema: o.parametersJsonSchema,
        source: 'mcp',
        status: 0
      }))
  } catch {
    return []
  }
}

/** 调用审计翻页独立加载，不重查工具表（筛选条件随查询串透传后端） */
async function loadLogs() {
  const logBody: any = await toolApi.logs({
    pageNum: logsPageNum.value,
    pageSize: logsPageSize.value,
    toolCode: logFilterTool.value.trim() || undefined,
    agentCode: logFilterAgent.value.trim() || undefined,
    sessionId: logFilterSession.value.trim() || undefined
  }).catch(() => null)
  logs.value = logBody?.records ?? []
  logsTotal.value = Number(logBody?.total ?? 0)
}

/** 审计筛选查询：重置到第一页 */
function searchLogs() {
  logsPageNum.value = 1
  loadLogs()
}

/** 关键字查询：重置到第一页 */
function search() {
  pageNum.value = 1
  load()
}

function resetCfg() {
  cfg.url = ''
  cfg.method = 'POST'
  cfg.headers = ''
  cfg.bodyTemplate = ''
  cfg.baseUrl = ''
  cfg.apiKey = ''
  cfg.appType = 'workflow'
  cfg.user = ''
  cfg.timeoutMillis = undefined
}

/** 回填外部工具连接配置（非法 JSON 静默忽略，交由保存时后端校验兜底） */
function parseCfg(row: any) {
  resetCfg()
  let c: any = {}
  try { c = JSON.parse(row.configJson || '{}') } catch { c = {} }
  cfg.url = c.url || ''
  cfg.method = c.method || 'POST'
  cfg.headers = c.headers ? JSON.stringify(c.headers, null, 2) : ''
  cfg.bodyTemplate = c.bodyTemplate || ''
  cfg.baseUrl = c.baseUrl || ''
  cfg.apiKey = c.apiKey || ''
  cfg.appType = c.appType || 'workflow'
  cfg.user = c.user || ''
  cfg.timeoutMillis = c.timeoutMillis || undefined
}

/** 表单字段序列化为 configJson（校验失败返回 undefined 中断保存） */
function buildCfg(): string | null | undefined {
  const s = form.value.source
  if (s === 'http') {
    if (!cfg.url || !cfg.url.trim()) {
      ElMessage.warning('请填写接口地址')
      return undefined
    }
    const c: any = { url: cfg.url.trim(), method: cfg.method || 'POST' }
    if (cfg.headers && cfg.headers.trim()) {
      try { c.headers = JSON.parse(cfg.headers) } catch {
        ElMessage.warning('请求头不是合法 JSON 对象')
        return undefined
      }
    }
    if (cfg.bodyTemplate && cfg.bodyTemplate.trim()) c.bodyTemplate = cfg.bodyTemplate
    if (cfg.timeoutMillis) c.timeoutMillis = Number(cfg.timeoutMillis)
    return JSON.stringify(c)
  }
  if (s === 'dify') {
    if (!cfg.apiKey || !cfg.apiKey.trim()) {
      ElMessage.warning('请填写 Dify 应用 API Key')
      return undefined
    }
    const c: any = { apiKey: cfg.apiKey.trim(), appType: cfg.appType || 'workflow' }
    if (cfg.baseUrl && cfg.baseUrl.trim()) c.baseUrl = cfg.baseUrl.trim()
    if (cfg.user && cfg.user.trim()) c.user = cfg.user.trim()
    if (cfg.timeoutMillis) c.timeoutMillis = Number(cfg.timeoutMillis)
    return JSON.stringify(c)
  }
  return null
}

function openEdit() {
  // 租户端仅可登记 http/dify 外部工具（rpc 指向内部服务，仅平台端开放）
  form.value = { source: authStore.platform ? 'rpc' : 'http', status: 0, parametersJsonSchema: '{"type":"object","properties":{}}' }
  resetCfg()
  loadRpcGroups()
  editVisible.value = true
}

/** 编辑既有登记（toolCode 是智能体绑定的主键，编辑态锁定不可改） */
function editTool(row: AiTool) {
  form.value = { ...row }
  parseCfg(row)
  loadRpcGroups()
  editVisible.value = true
}

async function save() {
  if (!form.value.toolCode) {
    ElMessage.warning('工具编码必填')
    return
  }
  if (form.value.source === 'rpc' && !form.value.rpcGroup) {
    ElMessage.warning('rpc 工具需填写提供方服务名')
    return
  }
  // 模型可见性校验（与后端 save 一致）：仅对本次可编辑 Schema 的来源生效——
  // builtin/local 与编辑态 rpc 的内容以提供方代码/推送为准，不拦启停保存
  if (!schemaReadonly.value) {
    if (!form.value.description || !form.value.description.trim()) {
      ElMessage.warning('功能说明必填（模型据此判断何时调用该工具）')
      return
    }
    const schemaText = form.value.parametersJsonSchema || ''
    if (schemaText.trim()) {
      let schema: any
      try {
        schema = JSON.parse(schemaText)
      } catch {
        ElMessage.warning('参数 Schema 不是合法 JSON 对象')
        return
      }
      const missing = Object.entries(schema?.properties ?? {})
        .filter(([, p]) => !((p as any)?.description || '').trim())
        .map(([n]) => n)
      if (missing.length) {
        ElMessage.warning(`参数缺少说明（模型无法识别参数用途）: ${missing.join('、')}`)
        return
      }
    }
  }
  const configJson = buildCfg()
  if (configJson === undefined) return
  form.value.configJson = configJson
  await toolApi.save(form.value)
  ElMessage.success(form.value.id ? '已保存' : '已登记')
  editVisible.value = false
  await load()
}

/** 测试工具：弹窗按参数 Schema 双模式填写（表单逐项填值 / JSON 源码直贴） */
function testTool(row: AiTool) {
  testRow.value = row
  testVisible.value = true
}

async function remove(row: AiTool) {
  const systemHint = row.source === 'local' || row.source === 'builtin'
    ? '（系统工具会在服务重启后自动重新登记）' : ''
  await ElMessageBox.confirm(`删除工具登记「${row.toolCode}」？${systemHint}`, '确认', { type: 'warning' })
  await toolApi.remove(row.id)
  await load()
}

function sourceText(source: string) {
  return { builtin: '内置', local: '本地', rpc: 'RPC', http: 'HTTP', dify: 'Dify', mcp: 'MCP' }[source] || source
}

/** 提供方/目标：rpc 显示 Dubbo group，mcp 显示接入的服务名，http/dify 显示目标地址 */
function targetText(row: AiTool) {
  if (row.source === 'mcp') return row.toolCode.split(':')[1] || '—'
  if (row.source === 'rpc') return row.rpcGroup || '—'
  try {
    const c: any = JSON.parse(row.configJson || '{}')
    return c.url || c.baseUrl || '—'
  } catch {
    return '—'
  }
}

/** toolCode 映射登记名称（未登记的编码原样保留） */
function toolName(toolCode?: string) {
  if (!toolCode) return '—'
  return rows.value.find((t) => t.toolCode === toolCode)?.name || toolCode
}
function sourceType(source: string) {
  return ({ builtin: 'success', local: 'primary', rpc: 'warning', http: 'info', dify: 'info', mcp: 'danger' } as const)[source] || 'info'
}
</script>

<style scoped>
.page {
  padding: 12px;
}
.intro {
  margin-bottom: 8px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.source-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.source-tip {
  margin: -8px 0 12px 100px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.schema-box {
  width: 100%;
}
.schema-summary {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.schema-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 2px;
}
.schema-text {
  font-size: 12px;
  color: var(--el-text-color-regular);
  line-height: 1.5;
}
</style>
