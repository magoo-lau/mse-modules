<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="名称/编码" style="width: 200px" clearable @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
      <el-button v-permission="'ai:agent:save'" type="primary" @click="openEdit()">新建智能体</el-button>
    </div>
    <el-table :data="rows" border size="small">
      <el-table-column prop="agentCode" label="编码" width="140" />
      <el-table-column label="名称" width="160">
        <template #default="{ row }">
          <span class="agent-name-cell">
            <agent-avatar :avatar="row.avatar" :name="row.name" :size="20" />
            <span>{{ row.name }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="模型" width="120">
        <template #default="{ row }">{{ modelName(row.modelId) }}</template>
      </el-table-column>
      <el-table-column label="编排" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.orchestrationType === 'flow'" type="warning" size="small">画布编排</el-tag>
          <el-tag v-else type="info" size="small" effect="plain">简单配置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="100">
        <template #default="{ row }">
          <el-tag :type="sourceType(row.source)" size="small" effect="plain">{{ sourceText(row.source) }}</el-tag>
          <span v-if="row.source === 'rpc' && row.rpcGroup" class="hint" style="margin-left: 4px">{{ row.rpcGroup }}</span>
        </template>
      </el-table-column>
      <el-table-column label="工具" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ toolNames(row.toolCodes) }}</template>
      </el-table-column>
      <el-table-column label="知识库" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ kbNames(row.kbIds) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
            {{ row.status === 0 ? '已发布' : '下线' }}
          </el-tag>
          <el-tag v-if="row.draftGraphJson && row.draftGraphJson !== row.graphJson" type="warning" size="small"
                  style="margin-left: 4px">有草稿</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="310" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'ai:agent:save'" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.orchestrationType !== 'flow'" v-permission="'ai:agent:save'" link type="warning"
            @click="openDebug(row)">调试</el-button>
          <el-button v-if="row.orchestrationType === 'flow'" v-permission="'ai:agent:save'" link type="warning"
            @click="$router.push(`/agent/flow/${row.id}`)">画布</el-button>
          <el-button v-if="row.orchestrationType === 'flow'" v-permission="'ai:agent:save'" link type="success"
            @click="publish(row)">发布</el-button>
          <el-button v-if="row.orchestrationType === 'flow'" v-permission="'ai:agent:list'" link
            @click="openRuns(row)">运行</el-button>
          <el-button v-permission="'ai:agent:delete'" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="total" v-model:page="pageNum" v-model:size="pageSize" @change="load" />

    <el-dialog v-model="editVisible" :title="dialogTitle" width="640px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="编码" required>
          <el-input v-model="form.agentCode" placeholder="唯一编码，如 oa-approval-helper"
                    :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="declaredPlatform" />
        </el-form-item>
        <el-form-item label="头像">
          <agent-avatar-picker v-model="form.avatar" />
          <div class="flow-hint">系统内置图标或本地上传图片（≤2MB）；对话工作台与 IM 机器人头像均使用</div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" :disabled="declaredPlatform" />
        </el-form-item>
        <el-form-item label="系统提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="5" placeholder="支持 {{var}} 占位"
                    :disabled="declaredPlatform" />
          <div v-if="declaredPlatform" class="flow-hint">
            编码/名称/描述/提示词/工具由提供方模块声明维护（随服务启动自动登记同步），此处仅可调整头像/模型/温度/开场白/启停/备注
          </div>
        </el-form-item>
        <el-form-item label="绑定模型">
          <el-select v-model="form.modelId" clearable filterable placeholder="系统默认模型" style="width: 100%">
            <el-option v-for="m in models" :key="m.id" :label="`${m.name}（${m.chatModel}）`" :value="m.id" />
          </el-select>
          <div class="flow-hint">仅列出对话模型；向量模型在知识库中绑定</div>
        </el-form-item>
        <el-form-item label="温度">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 280px" />
        </el-form-item>
        <el-form-item label="绑定工具">
          <el-select v-model="selectedToolCodes" multiple filterable allow-create default-first-option
                     :disabled="declaredPlatform"
                     collapse-tags collapse-tags-tooltip style="width: 100%"
                     placeholder="不绑定 = 纯对话；可勾选也可输入自定义编码（如 mcp:maps:geo）">
            <el-option v-for="t in toolOptions" :key="t.toolCode"
                       :label="`${t.name || t.toolCode}（${t.toolCode}）`" :value="t.toolCode">
              <div class="tool-option">
                <span>{{ t.name || t.toolCode }}（{{ t.toolCode }}）</span>
                <span v-if="t.description" class="tool-option-desc">{{ t.description }}</span>
              </div>
            </el-option>
          </el-select>
          <div class="flow-hint">勾选后智能体在对话中按需调用；工具用途见选项说明</div>
        </el-form-item>
        <el-form-item label="绑定知识库">
          <el-select
            v-model="selectedKbIds"
            multiple
            clearable
            collapse-tags
            collapse-tags-tooltip
            placeholder="不绑定 = 纯对话（多选启用 RAG 检索增强）"
            style="width: 100%"
          >
            <el-option v-for="kb in kbOptions" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="编排模式">
          <el-radio-group v-model="form.orchestrationType" :disabled="declaredPlatform">
            <el-radio value="simple">简单配置（提示词 + 工具 + 知识库）</el-radio>
            <el-radio value="flow">画布编排（工作流节点）</el-radio>
          </el-radio-group>
          <div v-if="form.orchestrationType === 'flow'" class="flow-hint">
            画布编辑器保存为<b>草稿</b>（调试按草稿运行），需在列表点「发布」才会更新线上版本；提示词/工具/知识库配置仅简单模式生效
          </div>
        </el-form-item>
        <el-form-item label="直出图表">
          <el-switch v-model="form.chartDirect" :active-value="1" :inactive-value="0"
                     :disabled="declaredPlatform || form.orchestrationType === 'flow'" />
          <div class="flow-hint">开启后数据问答只回一句话极简结论，查询结果由图表卡片直出；SQL 输出列强制中文别名、枚举值中文映射（仅简单模式生效）</div>
        </el-form-item>
        <el-form-item label="开场白">
          <el-input v-model="form.welcomeMessage" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="0">已发布</el-radio>
            <el-radio :value="1">下线</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">关闭</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 运行历史 -->
    <el-dialog v-model="runsVisible" :title="`运行历史 - ${runsAgent?.name || ''}`" width="960px">
      <el-table :data="runRows" size="small" border v-loading="runsLoading" max-height="400">
        <el-table-column prop="createTime" label="时间" width="160" />
        <el-table-column label="通道" width="90" align="center">
          <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'success' ? 'success' : row.status === 'cancelled' ? 'info' : 'danger'"
                    size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="queryText" label="输入" min-width="160" show-overflow-tooltip />
        <el-table-column prop="answer" label="回答" min-width="160" show-overflow-tooltip />
        <el-table-column prop="error" label="失败原因" min-width="120" show-overflow-tooltip />
        <el-table-column prop="elapsedMs" label="耗时(ms)" width="90" align="center" />
        <el-table-column prop="nodeTotal" label="节点" width="60" align="center" />
        <el-table-column label="操作" width="70" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRunDetail(row)">轨迹</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="runTotal" v-model:page="runPageNum" v-model:size="runPageSize" @change="loadRuns" />
    </el-dialog>

    <el-drawer v-model="runDetailVisible" title="运行轨迹回放" size="560px">
      <template v-if="runDetail">
        <div class="run-meta">
          <el-tag :type="runDetail.status === 'success' ? 'success' : runDetail.status === 'cancelled' ? 'info' : 'danger'"
                  size="small">{{ statusLabel(runDetail.status) }}</el-tag>
          <span class="hint">{{ channelLabel(runDetail.channel) }} · {{ runDetail.elapsedMs }}ms · {{ runDetail.nodeTotal }} 节点</span>
        </div>
        <div v-if="runDetail.error" class="run-error">{{ runDetail.error }}</div>
        <el-timeline style="margin-top: 12px">
          <el-timeline-item v-for="(t, i) in runTraces" :key="i"
                            :type="t.status === 'success' ? 'success' : t.status === 'failed' ? 'danger' : 'warning'"
                            :timestamp="`${t.nodeId} · ${t.elapsedMs ?? 0}ms`">
            <div class="trace-title">{{ t.title || t.nodeId }}（{{ t.nodeType }}）</div>
            <div v-if="t.output" class="trace-output">{{ t.output }}</div>
            <div v-if="t.error" class="trace-error">{{ t.error }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <!-- 简单配置智能体调试台：一次性试跑（不落会话/记忆），全过程时间线（每轮模型输入/输出、工具入参/返回、耗时、token） -->
    <AgentDebugDrawer v-model:visible="debugVisible" :agent="debugAgent" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi, kbApi, modelApi, toolApi } from '../api/ai'
import type { AiAgent, AiKnowledgeBase, AiModel, ToolOption } from '../api/ai'
import AgentAvatar from '../components/AgentAvatar.vue'
import AgentAvatarPicker from '../components/AgentAvatarPicker.vue'
import AgentDebugDrawer from '../components/AgentDebugDrawer.vue'
import { FLOW_TEMPLATES } from './flow/template'
import { Pagination } from 'mse-ui-shared/pc'

const router = useRouter()

const rows = ref<AiAgent[]>([])
const models = ref<AiModel[]>([])
const kbOptions = ref<AiKnowledgeBase[]>([])
const selectedKbIds = ref<string[]>([])
const toolOptions = ref<ToolOption[]>([])
const selectedToolCodes = ref<string[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const editVisible = ref(false)
const form = ref<any>({})

const dialogTitle = computed(() => (form.value.id ? '编辑智能体' : '新建智能体'))

/** 平台世界的声明登记行（local=单体声明 / rpc=微服务推送）：内容与编排由提供方模块权威维护 */
const declaredPlatform = computed(() => !!form.value.id && form.value.scope === 'platform'
  && (form.value.source === 'local' || form.value.source === 'rpc'))

function sourceText(source?: string) {
  return ({ builtin: '内置', local: '模块声明', rpc: '模块注册', custom: '自建' } as const)[source || 'custom'] || source
}

function sourceType(source?: string) {
  return ({ builtin: 'success', local: 'primary', rpc: 'warning', custom: 'info' } as const)[source || 'custom'] || 'info'
}

onMounted(async () => {
  await load()
  // 仅对话模型：向量模型用于知识库 RAG 绑定
  models.value = ((await modelApi.available('chat').catch(() => [])) as any) ?? []
  const kbBody: any = await kbApi.page({ pageNum: 1, pageSize: 100 }).catch(() => null)
  kbOptions.value = kbBody?.records ?? []
  toolApi.options().then((opts: any) => (toolOptions.value = opts ?? [])).catch(() => {})
})

async function load() {
  const body: any = await agentApi.page({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: keyword.value })
  rows.value = body?.records ?? []
  total.value = Number(body?.total ?? 0)
}

function modelName(modelId?: string) {
  if (!modelId) return '默认模型'
  return models.value.find((m) => m.id === modelId)?.name || modelId
}

/** CSV toolCode 串映射为工具名称，未知编码原样保留 */
function toolNames(csv?: string) {
  if (!csv) return '—'
  return csv.split(',').map((code) => {
    const c = code.trim()
    const t = toolOptions.value.find((x) => x.toolCode === c)
    return t?.name || c
  }).join('、')
}

/** CSV id 串转知识库名称（列表列展示） */
function kbNames(csv?: string) {
  if (!csv) return '—'
  const names = csv.split(',').map((id) => kbOptions.value.find((kb) => kb.id === id.trim())?.name || id.trim())
  return names.join('、')
}

function openEdit(row?: AiAgent) {
  form.value = row ? { ...row } : { status: 0, orchestrationType: 'simple', chartDirect: 0 }
  selectedKbIds.value = (form.value.kbIds || '').split(',').map((s: string) => s.trim()).filter(Boolean)
  selectedToolCodes.value = (form.value.toolCodes || '').split(',').map((s: string) => s.trim()).filter(Boolean)
  editVisible.value = true
}

async function save() {
  if (!form.value.agentCode || !form.value.name) {
    ElMessage.warning('编码与名称必填')
    return
  }
  form.value.kbIds = selectedKbIds.value.join(',')
  form.value.toolCodes = selectedToolCodes.value.join(',')
  const created = form.value.orchestrationType === 'flow' && !form.value.id
  // 编辑既有画布智能体时不回传 graphJson（管理弹窗不编辑画布，回传运行版会误覆盖未发布草稿）；
  // 画布草稿只经编辑器「保存草稿」写入
  const payload: any = { ...form.value }
  if (form.value.orchestrationType === 'flow' && form.value.id) {
    delete payload.graphJson
  }
  await agentApi.save(payload)
  ElMessage.success('已保存')
  editVisible.value = false
  await load()
  // 新建画布编排：套用数据分析模板后直接进入编排画布（复制的画布自带 DSL，不套模板）
  if (created) {
    const row = rows.value.find((r) => r.agentCode === form.value.agentCode)
    if (row && (await applyTemplate(row))) {
      await router.push(`/agent/flow/${row.id}`)
    }
  }
}

/** 新建 flow 智能体后套用「数据分析助手」模板（可跳过留白画布） */
async function applyTemplate(row: AiAgent) {
  try {
    await ElMessageBox.confirm('是否套用「数据分析助手」编排模板？\n（取消则打开空白画布）', '编排模板', {
      type: 'info',
      confirmButtonText: '套用模板',
      cancelButtonText: '空白画布'
    })
  } catch {
    return true
  }
  const template = FLOW_TEMPLATES[0]
  await agentApi.save({ ...row, graphJson: JSON.stringify(template.graph) })
  ElMessage.success('已套用模板，可继续调整节点配置')
  await load()
  return true
}

async function remove(row: AiAgent) {
  await ElMessageBox.confirm(`删除智能体「${row.name}」？`, '确认', { type: 'warning' })
  await agentApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

/** 发布：草稿编排图校验后拷贝为运行版（对话/RPC 即刻生效） */
async function publish(row: AiAgent) {
  await agentApi.publish(row.id)
  ElMessage.success('已发布（草稿已生效为线上版本）')
  await load()
}

// ==================== 运行历史 ====================

const runsVisible = ref(false)
const runsAgent = ref<AiAgent | null>(null)
const runRows = ref<any[]>([])
const runTotal = ref(0)
const runPageNum = ref(1)
const runPageSize = ref(10)
const runsLoading = ref(false)
const runDetailVisible = ref(false)
const runDetail = ref<any>(null)
const runTraces = ref<any[]>([])

const CHANNEL_LABELS: Record<string, string> = {
  gateway: '对话', debug: '调试', rpc: 'RPC', openapi: '开放API'
}
const STATUS_LABELS: Record<string, string> = {
  success: '成功', failed: '失败', cancelled: '已取消'
}

function channelLabel(channel?: string) {
  return channel ? CHANNEL_LABELS[channel] || channel : '—'
}

function statusLabel(status?: string) {
  return status ? STATUS_LABELS[status] || status : '—'
}

function openRuns(row: AiAgent) {
  runsAgent.value = row
  runPageNum.value = 1
  runsVisible.value = true
  loadRuns()
}

async function loadRuns() {
  if (!runsAgent.value) return
  runsLoading.value = true
  try {
    const body: any = await agentApi.runPage({
      pageNum: runPageNum.value, pageSize: runPageSize.value, agentCode: runsAgent.value.agentCode
    })
    runRows.value = body?.records ?? []
    runTotal.value = Number(body?.total ?? 0)
  } finally {
    runsLoading.value = false
  }
}

async function openRunDetail(row: any) {
  runDetail.value = await agentApi.runDetail(row.id)
  try {
    runTraces.value = JSON.parse(runDetail.value?.tracesJson || '[]')
  } catch {
    runTraces.value = []
  }
  runDetailVisible.value = true
}

// ==================== 简单配置智能体调试台 ====================

const debugVisible = ref(false)
const debugAgent = ref<AiAgent | null>(null)

function openDebug(row: AiAgent) {
  debugAgent.value = row
  debugVisible.value = true
}
</script>

<style scoped>
.page {
  padding: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.flow-hint {
  width: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.tool-option {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}
.tool-option-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: normal;
  word-break: break-all;
}
.run-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.run-error {
  margin-top: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 12px;
}
.trace-title {
  font-size: 13px;
  font-weight: 600;
}
.trace-output {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}
.trace-error {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-color-danger);
  white-space: pre-wrap;
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.agent-name-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
