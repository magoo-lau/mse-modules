<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="名称/模型名" style="width: 200px" clearable @keyup.enter="search" />
      <el-select v-model="modelTypeFilter" style="width: 130px" @change="search">
        <el-option label="全部类型" value="" />
        <el-option label="对话模型" value="chat" />
        <el-option label="向量模型" value="embedding" />
        <el-option label="重排模型" value="rerank" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button v-permission="'ai:model:save'" type="primary" @click="openEdit()">新增模型</el-button>
    </div>
    <el-table :data="models" border size="small">
      <el-table-column prop="name" label="名称" width="140" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">
          <el-tag v-if="modelTypeOf(row) === 'embedding'" type="warning" size="small">向量模型</el-tag>
          <el-tag v-else-if="modelTypeOf(row) === 'rerank'" type="danger" size="small">重排模型</el-tag>
          <el-tag v-else size="small">对话模型</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="供应商" width="130">
        <template #default="{ row }">{{ providerLabel(row.provider) }}</template>
      </el-table-column>
      <el-table-column label="协议" width="110">
        <template #default="{ row }">{{ protocolLabel(row.protocol) }}</template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="端点" min-width="180" show-overflow-tooltip />
      <el-table-column label="模型名" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ modelTypeOf(row) === 'embedding' ? row.embeddingModel : row.chatModel }}
        </template>
      </el-table-column>
      <el-table-column label="连通性" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.lastTestOk === 1" type="success" size="small">通过</el-tag>
          <el-tooltip v-else-if="row.lastTestOk === 0" :content="row.lastTestMsg" placement="top">
            <el-tag type="danger" size="small">失败</el-tag>
          </el-tooltip>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
            {{ row.status === 0 ? '启用' : '停用' }}
          </el-tag>
          <el-tag v-if="modelTypeOf(row) === 'embedding' && row.isDefault === 1" type="warning" size="small" style="margin-left: 4px">
            默认
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'ai:model:test'" link type="primary" @click="test(row)">测试</el-button>
          <el-button v-if="modelTypeOf(row) === 'embedding' && row.isDefault !== 1"
                     v-permission="'ai:model:save'" link type="primary" @click="setDefault(row)">设默认</el-button>
          <el-button v-permission="'ai:model:save'" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-permission="'ai:model:delete'" link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="modelTotal" v-model:page="modelPageNum" v-model:size="modelPageSize" @change="load" />

    <!-- 模型编辑 -->
    <el-dialog v-model="editVisible" :title="form.id ? '编辑模型' : '新增模型'" width="600px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="模型类型" required>
          <el-radio-group v-model="form.modelType" :disabled="!!form.id" @change="onTypeChange">
            <el-radio value="chat">对话模型</el-radio>
            <el-radio value="embedding">向量模型</el-radio>
            <el-radio value="rerank">重排模型</el-radio>
          </el-radio-group>
          <div class="field-hint">
            对话模型供智能体/工作流绑定；向量/重排模型供知识库 RAG 绑定，一行只表达一种能力
          </div>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 DeepSeek-V3 / 智谱 embedding-3" />
        </el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="form.provider" style="width: 100%" clearable
                     placeholder="选择供应商自动带出内置端点" @change="onProviderChange">
            <el-option v-for="p in providerPresets" :key="p.code" :label="p.label" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.modelType === 'chat'" label="接口类型">
          <el-select v-model="form.protocol" style="width: 100%">
            <el-option label="OpenAI 兼容（/chat/completions）" value="openai-chat" />
            <el-option label="Claude 接口（/v1/messages）" value="anthropic-messages" />
          </el-select>
          <div class="field-hint">
            Responses API 暂不支持（Spring AI 2.0.1 上游未实现）；向量/重排模型固定走 OpenAI 兼容协议
          </div>
        </el-form-item>
        <el-form-item label="端点" required>
          <el-select v-model="form.baseUrl" filterable allow-create default-first-option style="width: 100%"
                     placeholder="选择内置端点或直接输入" @change="onBaseUrlChange">
            <el-option v-for="e in endpointOptions" :key="e.url" :value="e.url"
                       :label="`${e.url}（${PROTOCOL_LABELS[e.protocol]}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="form.id ? '留空保持不变' : keyPlaceholder" />
        </el-form-item>
        <el-form-item v-if="form.modelType !== 'embedding'" :label="form.modelType === 'rerank' ? '重排模型名' : '对话模型'" required>
          <el-input v-model="form.chatModel" :placeholder="form.modelType === 'rerank' ? rerankModelPlaceholder : chatModelPlaceholder" />
        </el-form-item>
        <el-form-item v-else label="向量模型" required>
          <el-input v-model="form.embeddingModel" :placeholder="embeddingModelPlaceholder" />
        </el-form-item>
        <el-form-item v-if="form.modelType === 'chat'" label="温度">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" :precision="1" />
        </el-form-item>
        <el-form-item v-if="form.modelType === 'chat'" label="备用模型">
          <el-select v-model="form.failoverModelId" clearable filterable placeholder="无（瞬态错误仅重试不切换）"
                     style="width: 100%">
            <el-option v-for="m in failoverOptions" :key="m.id"
                       :label="`${m.name}（${m.chatModel}）`" :value="m.id" />
          </el-select>
          <div class="field-hint">主模型遇 5xx/限流/网络错误自动重试，重试仍失败时切换本模型再试（对话/RPC/工作流全链路生效）</div>
        </el-form-item>
        <el-form-item v-if="form.modelType === 'embedding'" label="设为默认">
          <el-switch v-model="form.isDefaultOn" active-text="知识库未指定向量模型时使用本模型" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="0">启用</el-radio>
            <el-radio :value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { modelApi } from '../api/ai'
import type { AiModel } from '../api/ai'
import {
  PROTOCOL_LABELS,
  findPreset,
  providerLabel,
  protocolLabel,
  providerPresets
} from '../constants/providers'
import { Pagination } from 'mse-ui-shared/pc'

const models = ref<AiModel[]>([])
const keyword = ref('')
const modelTypeFilter = ref('')
const modelTotal = ref(0)
const modelPageNum = ref(1)
const modelPageSize = ref(20)
const editVisible = ref(false)
const form = ref<any>({})

/** 行类型归一（历史数据空类型按对话模型） */
function modelTypeOf(row: AiModel): 'chat' | 'embedding' | 'rerank' {
  return row.modelType === 'embedding' ? 'embedding' : row.modelType === 'rerank' ? 'rerank' : 'chat'
}

/** 弹窗候选对话模型（available 接口全量返回，不随表格分页） */
const chatModels = ref<AiModel[]>([])

/** 备用模型候选：排除当前编辑行自身 */
const failoverOptions = computed(() => chatModels.value.filter((m) => m.id !== form.value.id))

/** 拉取弹窗候选对话模型（失败置空仅影响下拉候选） */
function loadChatModels() {
  modelApi.available('chat').then((list: any) => (chatModels.value = list ?? [])).catch(() => (chatModels.value = []))
}

/** 当前供应商预设：端点下拉 / 各字段 placeholder 联动 */
const preset = computed(() => findPreset(form.value.provider))
/** 向量/重排模型固定 OpenAI 兼容协议：端点下拉过滤出 openai-chat 内置端点 */
const endpointOptions = computed(() =>
  (preset.value?.endpoints ?? []).filter(
    (e) => (form.value.modelType !== 'embedding' && form.value.modelType !== 'rerank') || e.protocol === 'openai-chat'
  )
)
const chatModelPlaceholder = computed(
  () => preset.value?.chatModelPlaceholder ?? '按端点文档填写模型名'
)
const embeddingModelPlaceholder = computed(
  () => preset.value?.embeddingModelPlaceholder ?? '向量模型名，如 embedding-3 / nomic-embed-text'
)
const rerankModelPlaceholder = computed(
  () => preset.value?.rerankModelPlaceholder ?? '重排模型名，如 BAAI/bge-reranker-v2-m3'
)
const keyPlaceholder = computed(() => preset.value?.keyPlaceholder ?? 'sk-...')

/** 切换模型类型：向量/重排模型固定 OpenAI 兼容协议；当前端点不兼容时带出首个内置端点 */
function onTypeChange(type: string) {
  if (type === 'embedding' || type === 'rerank') {
    form.value.protocol = 'openai-chat'
    onProviderChange(form.value.provider)
  }
}

/** 切换供应商：当前端点为空或为其他供应商内置端点时，自动带出首个内置端点与接口类型 */
function onProviderChange(code?: string) {
  const p = code ? findPreset(code) : undefined
  const current = form.value.baseUrl
  const isPresetUrl = providerPresets.some((x) => x.endpoints.some((e) => e.url === current))
  if (p && p.endpoints.length && (!current || isPresetUrl)) {
    form.value.baseUrl = p.endpoints[0].url
    form.value.protocol = p.endpoints[0].protocol
  }
}

/** 选定端点命中内置预设时联动接口类型 */
function onBaseUrlChange(url: string) {
  const hit = endpointOptions.value.find((e) => e.url === url)
  if (hit) form.value.protocol = hit.protocol
}

onMounted(() => {
  load()
  loadChatModels()
})

async function load() {
  const body: any = await modelApi.page({
    pageNum: modelPageNum.value,
    pageSize: modelPageSize.value,
    keyword: keyword.value,
    modelType: modelTypeFilter.value || undefined
  })
  models.value = body?.records ?? []
  modelTotal.value = Number(body?.total ?? 0)
}

/** 模型查询：条件变化重置到第一页 */
function search() {
  modelPageNum.value = 1
  load()
}

function openEdit(row?: AiModel) {
  form.value = row
    ? {
        ...row,
        apiKey: '',
        embeddingApiKey: '',
        isDefaultOn: modelTypeOf(row) === 'embedding' && row.isDefault === 1
      }
    : { modelType: 'chat', status: 0, temperature: 0.7, protocol: 'openai-chat', isDefaultOn: false }
  editVisible.value = true
}

async function save() {
  if (!form.value.name || !form.value.baseUrl) {
    ElMessage.warning('名称/端点必填')
    return
  }
  if (form.value.modelType === 'embedding') {
    if (!form.value.embeddingModel) {
      ElMessage.warning('向量模型名必填')
      return
    }
    form.value.protocol = 'openai-chat'
    form.value.isDefault = form.value.isDefaultOn ? 1 : 0
  } else if (form.value.modelType === 'rerank') {
    if (!form.value.chatModel) {
      ElMessage.warning('重排模型名必填')
      return
    }
    form.value.protocol = 'openai-chat'
    form.value.isDefault = 0
  } else {
    if (!form.value.chatModel) {
      ElMessage.warning('对话模型名必填')
      return
    }
    form.value.isDefault = 0
  }
  await modelApi.save(form.value)
  ElMessage.success('已保存')
  editVisible.value = false
  await load()
  loadChatModels()
}

/** 设为默认向量模型（本世界唯一默认，覆盖其他向量行的标记） */
async function setDefault(row: AiModel) {
  await modelApi.setDefaultEmbedding(row.id)
  ElMessage.success(`「${row.name}」已设为默认向量模型`)
  await load()
}

async function test(row: AiModel) {
  try {
    const reply: any = await modelApi.test(row.id)
    ElMessage.success(`连通正常：${reply}`)
  } catch {
    /* 拦截器已提示 */
  }
  await load()
}

async function remove(row: AiModel) {
  await ElMessageBox.confirm(`删除模型「${row.name}」？`, '确认', { type: 'warning' })
  await modelApi.remove(row.id)
  await load()
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
.field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 2px;
}
</style>
