<template>
  <div class="page">
    <el-alert
      v-if="vectorStatus && vectorStatus.available && !vectorStatus.fullText"
      type="info"
      :closable="false"
      title="当前 Milvus 版本低于 2.5，全文检索降级为纯向量召回；升级 Milvus 2.5+ 可启用 BM25 混合检索"
      style="margin-bottom: 8px"
    />
    <div class="kb-layout">
      <!-- 知识库列表 -->
      <div class="kb-list">
        <div class="toolbar">
          <el-button v-permission="'ai:kb:save'" type="primary" @click="openEdit()">新建知识库</el-button>
        </div>
        <div
          v-for="kb in rows"
          :key="kb.id"
          class="kb-item"
          :class="{ active: kb.id === currentKbId }"
          @click="selectKb(kb)"
        >
          <div class="kb-name">
            📚 {{ kb.name }}
            <el-tag v-if="kb.code" size="small" type="info" style="margin-left: 4px">声明库 {{ kb.code }}</el-tag>
          </div>
          <div class="kb-desc">{{ methodLabel(kb.chunkMethod) }} · {{ kb.description || '—' }}</div>
        </div>
        <el-empty v-if="!rows.length" description="暂无知识库" :image-size="60" />
      </div>

      <!-- 详情：文档 / 分块 / 检索测试 -->
      <div class="kb-detail" v-if="currentKbId">
        <div class="detail-header">
          <div>
            <div class="detail-title">{{ currentKb?.name }}</div>
            <div class="detail-meta">
              切片：{{ methodLabel(currentKb?.chunkMethod) }} · {{ currentKb?.chunkSize ?? 800 }} token
              · 重叠 {{ currentKb?.chunkOverlap ?? 100 }} · 检索 {{ currentKb?.topK ?? 4 }} 条
              · 阈值 {{ ((currentKb?.similarityThreshold ?? 0.5) * 100).toFixed(0) }}%
              · 向量模型：{{ embeddingModelName(currentKb) }}
              <template v-if="rerankModelName(currentKb)"> · 重排：{{ rerankModelName(currentKb) }}</template>
              <template v-if="currentKb?.enhanceEnabled === 1">
                · 索引增强：开（{{ currentKb?.enhanceQuestions ?? 3 }} 问）
              </template>
            </div>
          </div>
          <div class="toolbar" style="margin-bottom: 0">
            <el-button v-permission="'ai:kb:save'" @click="openEdit(currentKb!)">编辑</el-button>
            <el-button
              v-permission="'ai:kb:upload'"
              @click="doReindex"
            >重嵌入</el-button>
            <el-button
              v-permission="'ai:kb:upload'"
              @click="doRebuild"
            >重建索引</el-button>
            <el-button v-permission="'ai:kb:save'" type="danger" plain @click="removeKb">删除知识库</el-button>
          </div>
        </div>

        <el-tabs v-model="activeTab">
          <!-- ============ 文档 ============ -->
          <el-tab-pane label="文档" name="documents">
            <div class="toolbar">
              <el-upload
                :show-file-list="false"
                :before-upload="doUpload"
                accept=".pdf,.docx,.doc,.md,.txt,.html,.pptx,.csv,.xlsx,.xls"
              >
                <el-button
                  v-permission="'ai:kb:upload'"
                  type="primary"
                >上传文档</el-button>
              </el-upload>
              <span class="form-tip">上传后后台解析，状态自动刷新；QA/表格切片请传 CSV/Excel</span>
            </div>
            <el-table :data="documents" border size="small">
              <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
              <el-table-column prop="fileType" label="类型" width="70" />
              <el-table-column label="大小" width="90">
                <template #default="{ row }">{{ formatSize(row.sizeBytes) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="chunkCount" label="切片" width="60" />
              <el-table-column prop="errorMsg" label="失败原因" min-width="120" show-overflow-tooltip />
              <el-table-column label="操作" width="190" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" @click="downloadDoc(row)">原文</el-button>
                  <el-button v-permission="'ai:kb:upload'" link type="primary" @click="doReparse(row)">重解析</el-button>
                  <el-button v-permission="'ai:kb:save'" link type="danger" @click="removeDoc(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- ============ 分块浏览 ============ -->
          <el-tab-pane label="分块" name="chunks">
            <div class="toolbar">
              <el-select
                v-model="chunkDocId"
                placeholder="选择文档"
                style="width: 260px"
                @change="loadChunks"
              >
                <el-option
                  v-for="doc in documents.filter((d) => d.status === 2)"
                  :key="doc.id"
                  :label="doc.fileName"
                  :value="doc.id"
                />
              </el-select>
              <el-button v-permission="'ai:kb:save'" :disabled="!chunkDocId" @click="openAddChunk">新增切片</el-button>
              <span class="form-tip">编辑/删除即时生效（自动重嵌入）</span>
            </div>
            <el-table :data="chunks" border size="small">
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="chunk-enhance">
                    <template v-if="keywordListOf(row).length || questionListOf(row).length">
                      <div v-if="keywordListOf(row).length" class="enhance-line">
                        <span class="lbl">关键词</span>
                        <el-tag v-for="k in keywordListOf(row)" :key="k" size="small" effect="plain" class="kw-tag">{{ k }}</el-tag>
                      </div>
                      <div v-if="questionListOf(row).length" class="enhance-line">
                        <span class="lbl">自动问题</span>
                        <div class="q-list">{{ questionListOf(row).map((q, i) => `${i + 1}. ${q}`).join('　') }}</div>
                      </div>
                    </template>
                    <div v-else class="enhance-line none">未增强（知识库未开索引增强、人工切片或增强降级）</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="seq" label="#" width="56" />
              <el-table-column prop="content" label="切片内容" min-width="300" show-overflow-tooltip />
              <el-table-column label="操作" width="130" fixed="right">
                <template #default="{ row }">
                  <el-button v-permission="'ai:kb:save'" link type="primary" @click="openEditChunk(row)">编辑</el-button>
                  <el-button v-permission="'ai:kb:save'" link type="danger" @click="removeChunk(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <Pagination :total="chunkTotal" v-model:page="chunkPageNum" v-model:size="chunkPageSize" @change="loadChunks" />
          </el-tab-pane>

          <!-- ============ 检索测试 ============ -->
          <el-tab-pane label="检索测试" name="search">
            <div class="search-box">
              <div style="display: flex; gap: 8px">
                <el-input
                  v-model="searchQuery"
                  placeholder="输入问题，测试混合检索命中"
                  @keyup.enter="doSearch"
                />
                <el-input-number v-model="searchTopK" :min="1" :max="20" />
                <el-button
                  v-permission="'ai:kb:search'"
                  type="primary"
                  @click="doSearch"
                >检索</el-button>
              </div>
              <div v-if="searchResult" class="search-meta">
                召回通道：{{ searchResult.fullText ? '向量 + BM25 全文（RRF 融合）' : '纯向量（服务端 < 2.5 降级）' }}
                · 重排：{{ searchResult.reranked ? '已启用' : '未启用' }}
                · TopK {{ searchResult.requestedTopK }}
              </div>
              <el-empty v-if="searchResult && !searchResult.hits?.length" description="无命中（可尝试降低相似度阈值）" :image-size="60" />
              <div v-for="(hit, i) in searchResult?.hits ?? []" :key="hit.chunkId" class="hit">
                <div class="hit-meta">
                  #{{ i + 1 }}
                  <el-tag size="small" type="primary" effect="plain">得分 {{ (hit.score * 100).toFixed(1) }}%</el-tag>
                  <el-tag v-if="hit.rerankScore != null" size="small" type="warning" effect="plain">
                    重排 {{ (hit.rerankScore * 100).toFixed(1) }}%
                  </el-tag>
                  · 📄 {{ hit.fileName || `文档 ${hit.docId}` }}
                </div>
                <div class="hit-content">{{ hit.content }}</div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
      <el-empty v-else description="选择左侧知识库" class="kb-detail" />
    </div>

    <!-- 新建/编辑知识库 -->
    <el-dialog v-model="editVisible" :title="form.id ? '编辑知识库' : '新建知识库'" width="500px">
      <el-form :model="form" label-width="96px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" />
        </el-form-item>
        <el-form-item label="向量模型">
          <el-select v-model="form.embeddingModelId" style="width: 100%" placeholder="系统默认向量模型">
            <el-option
              v-for="m in embeddingModels"
              :key="m.id"
              :label="`${m.name}（${m.embeddingModel}）${m.isDefault === 1 ? ' · 默认' : ''}`"
              :value="m.id"
            />
          </el-select>
          <span class="form-tip">向量模型在模型中心维护；改绑后需「重嵌入」</span>
        </el-form-item>
        <el-form-item label="重排模型">
          <el-select v-model="form.rerankModelId" style="width: 100%" placeholder="不启用重排（融合分直排）">
            <el-option
              v-for="m in rerankModels"
              :key="m.id"
              :label="`${m.name}（${m.chatModel}）`"
              :value="m.id"
            />
          </el-select>
          <span class="form-tip">启用后检索候选按相关性精排再取 topK；重排模型在模型中心维护（rerank 类型）</span>
        </el-form-item>
        <el-form-item label="切片方法">
          <el-select v-model="form.chunkMethod" style="width: 200px">
            <el-option v-for="m in chunkMethods" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
          <span class="form-tip">qa/table 需 CSV/Excel 源文件</span>
        </el-form-item>
        <el-form-item label="切片大小">
          <el-input-number v-model="form.chunkSize" :min="200" :max="4000" :step="100" />
          <span class="form-tip">token（默认 800）</span>
        </el-form-item>
        <el-form-item label="切片重叠">
          <el-input-number v-model="form.chunkOverlap" :min="0" :max="1000" :step="50" />
          <span class="form-tip">token（general/book 生效）</span>
        </el-form-item>
        <el-form-item label="检索条数">
          <el-input-number v-model="form.topK" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="相似度阈值">
          <el-input-number v-model="form.similarityThreshold" :min="0" :max="1" :step="0.05" :precision="2" />
          <span class="form-tip">低于该值的召回被过滤</span>
        </el-form-item>
        <el-form-item label="索引增强">
          <el-switch v-model="form.enhanceEnabled" :active-value="1" :inactive-value="0" active-text="自动关键词/问题" />
          <el-input-number
            v-if="form.enhanceEnabled === 1"
            v-model="form.enhanceQuestions"
            :min="1"
            :max="5"
            style="margin-left: 8px; width: 110px"
          />
        </el-form-item>
        <el-form-item v-if="form.enhanceEnabled === 1" label=" ">
          <span class="form-tip">
            解析入库时用对话模型逐切片生成关键词与「用户可能会问的问题」，前置进嵌入文本提升问句召回；
            模型取 mse.ai.kb.enhance-model-id 或系统默认模型，失败自动降级按原文嵌入。开关/问题数变更后需「重建索引」
          </span>
        </el-form-item>
        <el-alert
          v-if="form.id"
          type="info"
          :closable="false"
          title="修改切片方法/大小/重叠后，需点「重建索引」对存量文档重新切片"
        />
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增/编辑切片 -->
    <el-dialog v-model="chunkDialogVisible" :title="chunkForm.id ? '编辑切片' : '新增切片'" width="560px">
      <el-alert
        v-if="chunkForm.id && (keywordListOf(chunkForm).length || questionListOf(chunkForm).length)"
        type="warning"
        :closable="false"
        title="正文修改后，该切片的自动关键词/自动问题将清空（按新正文重嵌入）；需要增强请重新解析文档"
        style="margin-bottom: 8px"
      />
      <el-input v-model="chunkForm.content" type="textarea" :rows="10" placeholder="切片正文" />
      <template #footer>
        <el-button @click="chunkDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveChunk">保存（自动重嵌入）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi, modelApi } from '../api/ai'
import type { AiKbChunk, AiKbDocument, AiKnowledgeBase, AiModel, RetrievalResult } from '../api/ai'
import { Pagination } from 'mse-ui-shared/pc'

const chunkMethods = [
  { value: 'general', label: '通用（默认）' },
  { value: 'qa', label: '问答对（QA）' },
  { value: 'table', label: '表格（一行一片）' },
  { value: 'book', label: '书籍（标题层级）' },
  { value: 'one', label: '整篇' }
]

const rows = ref<AiKnowledgeBase[]>([])
const documents = ref<AiKbDocument[]>([])
const currentKbId = ref('')
const currentKb = ref<AiKnowledgeBase | null>(null)
const vectorStatus = ref<any>(null)
const editVisible = ref(false)
const form = ref<any>({})
const activeTab = ref('documents')

/** 可选向量模型（模型中心维护的 modelType=embedding 行，按租户圈定过滤） */
const embeddingModels = ref<AiModel[]>([])
/** 可选重排模型（模型中心维护的 modelType=rerank 行，按租户圈定过滤；空 = 不启用重排） */
const rerankModels = ref<AiModel[]>([])
/** 保存前快照：用于检测向量模型改绑（改绑后提示重嵌入） */
let snapshotEmbeddingId: string | undefined

let pollTimer: number | undefined

// 检索测试
const searchQuery = ref('')
const searchTopK = ref(4)
const searchResult = ref<RetrievalResult | null>(null)

// 分块管理
const chunkDocId = ref('')
const chunks = ref<AiKbChunk[]>([])
const chunkPageNum = ref(1)
const chunkPageSize = ref(10)
const chunkTotal = ref(0)
const chunkDialogVisible = ref(false)
const chunkForm = ref<any>({})

onMounted(async () => {
  await load()
  try {
    vectorStatus.value = await kbApi.vectorStoreStatus()
  } catch {
    vectorStatus.value = null
  }
  // 向量模型下拉：平台默认行排前
  embeddingModels.value = ((await modelApi.available('embedding').catch(() => [])) as any) ?? []
  // 重排模型下拉（可选能力，未配置 = 融合分直排）
  rerankModels.value = ((await modelApi.available('rerank').catch(() => [])) as any) ?? []
})

onUnmounted(() => stopPolling())

async function load() {
  const body: any = await kbApi.page({ pageNum: 1, pageSize: 100 })
  rows.value = body?.records ?? []
}

function selectKb(kb: AiKnowledgeBase) {
  currentKbId.value = kb.id
  currentKb.value = kb
  activeTab.value = 'documents'
  searchResult.value = null
  chunkDocId.value = ''
  chunks.value = []
  loadDocuments()
}

// ==================== 文档：上传 / 轮询 / 重解析 / 重建 ====================

async function loadDocuments() {
  const body: any = await kbApi.documents(currentKbId.value, { pageNum: 1, pageSize: 100 })
  documents.value = body?.records ?? []
  // 存在待处理/处理中文档时持续轮询，全部落定后停止
  if (documents.value.some((d) => d.status === 0 || d.status === 1)) {
    startPolling()
  } else {
    stopPolling()
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(() => {
    if (currentKbId.value) loadDocuments()
  }, 3000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

async function doUpload(file: File) {
  try {
    await kbApi.upload(currentKbId.value, file)
    ElMessage.success('已上传，后台解析中')
    await loadDocuments()
  } catch {
    /* request 拦截器已提示 */
  }
  return false // 阻止 el-upload 默认上传
}

async function doReparse(row: AiKbDocument) {
  try {
    await kbApi.reparse(currentKbId.value, row.id)
    ElMessage.success('已加入重新解析队列')
    await loadDocuments()
  } catch {
    /* 拦截器已提示 */
  }
}

async function downloadDoc(row: AiKbDocument) {
  try {
    await kbApi.downloadOriginal(currentKbId.value, row.id, row.fileName)
  } catch {
    /* download 已提示 */
  }
}

async function doRebuild() {
  await ElMessageBox.confirm(
    '重建索引将对全部文档按当前切片配置重新解析与向量化，期间检索结果可能不完整，确认？',
    '重建索引',
    { type: 'warning' }
  )
  const count: any = await kbApi.rebuild(currentKbId.value)
  ElMessage.success(`已提交 ${count} 个文档重建`)
  await loadDocuments()
}

/** 重嵌入（轻量重建）：不重解析文档，跨集合清场后按现有切片全量重嵌入（改绑向量模型后执行） */
async function doReindex() {
  await ElMessageBox.confirm(
    '重嵌入将按当前绑定的向量模型对全部切片重新向量化（不重解析文档），期间检索结果可能不完整，确认？',
    '重嵌入',
    { type: 'warning' }
  )
  await kbApi.reindex(currentKbId.value)
  ElMessage.success('已提交重嵌入，后台执行中')
}

async function removeDoc(row: AiKbDocument) {
  await ElMessageBox.confirm(`删除文档「${row.fileName}」及其向量数据？`, '确认', { type: 'warning' })
  await kbApi.removeDocument(currentKbId.value, row.id)
  if (chunkDocId.value === row.id) {
    chunkDocId.value = ''
    chunks.value = []
  }
  await loadDocuments()
}

// ==================== 分块管理 ====================

/** 切片增强列（JSON 数组串）宽容解析：非法/为空返回空数组 */
function listOf(json: unknown): string[] {
  if (!json || typeof json !== 'string') return []
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr.filter((x) => typeof x === 'string' && x.trim()).map((x) => String(x).trim()) : []
  } catch {
    return []
  }
}
const keywordListOf = (row: any) => listOf(row?.keywords)
const questionListOf = (row: any) => listOf(row?.questions)

async function loadChunks() {
  if (!chunkDocId.value) return
  chunkPageNum.value = 1
  await loadChunkPage()
}

async function loadChunkPage() {
  const body: any = await kbApi.chunks(currentKbId.value, chunkDocId.value, {
    pageNum: chunkPageNum.value,
    pageSize: chunkPageSize.value
  })
  chunks.value = body?.records ?? []
  chunkTotal.value = Number(body?.total ?? 0)
}

function openAddChunk() {
  chunkForm.value = { id: '', content: '' }
  chunkDialogVisible.value = true
}

function openEditChunk(row: AiKbChunk) {
  chunkForm.value = { id: row.id, content: row.content }
  chunkDialogVisible.value = true
}

async function saveChunk() {
  if (!chunkForm.value.content.trim()) {
    ElMessage.warning('切片内容不能为空')
    return
  }
  if (chunkForm.value.id) {
    await kbApi.updateChunk(currentKbId.value, chunkForm.value.id, chunkForm.value.content)
  } else {
    await kbApi.addChunk(currentKbId.value, chunkDocId.value, chunkForm.value.content)
  }
  ElMessage.success('已保存并重嵌入')
  chunkDialogVisible.value = false
  await loadChunkPage()
  await loadDocuments()
}

async function removeChunk(row: AiKbChunk) {
  await ElMessageBox.confirm('删除该切片及其向量数据？', '确认', { type: 'warning' })
  await kbApi.removeChunk(currentKbId.value, row.id)
  await loadChunkPage()
  await loadDocuments()
}

// ==================== 检索测试 ====================

async function doSearch() {
  if (!searchQuery.value.trim()) return
  searchResult.value = (await kbApi.search({
    kbId: currentKbId.value,
    query: searchQuery.value,
    topK: searchTopK.value
  })) as any
}

// ==================== 知识库 CRUD ====================

function openEdit(row?: AiKnowledgeBase) {
  form.value = row
    ? { enhanceEnabled: 0, enhanceQuestions: 3, ...row }
    : {
        chunkSize: 800,
        chunkMethod: 'general',
        chunkOverlap: 100,
        topK: 4,
        similarityThreshold: 0.5,
        status: 0,
        enhanceEnabled: 0,
        enhanceQuestions: 3,
        // 新建默认选中平台默认向量模型行
        embeddingModelId: embeddingModels.value.find((m) => m.isDefault === 1)?.id
      }
  snapshotEmbeddingId = form.value.embeddingModelId
  editVisible.value = true
}

async function save() {
  if (!form.value.name) {
    ElMessage.warning('名称必填')
    return
  }
  await kbApi.save(form.value)
  ElMessage.success('已保存')
  editVisible.value = false
  const rebound =
    currentKbId.value === form.value.id &&
    (form.value.embeddingModelId ?? '') !== (snapshotEmbeddingId ?? '')
  await load()
  // 选中行数据已刷新，同步当前选中引用
  if (currentKbId.value) {
    currentKb.value = rows.value.find((r) => r.id === currentKbId.value) ?? currentKb.value
  }
  if (rebound) {
    try {
      await ElMessageBox.confirm(
        '向量模型已变更：需「重嵌入」对存量切片重新向量化后检索才生效，是否立即执行？',
        '向量模型已变更',
        { type: 'warning', confirmButtonText: '立即重嵌入', cancelButtonText: '稍后手动执行' }
      )
      await kbApi.reindex(currentKbId.value)
      ElMessage.success('已提交重嵌入，后台执行中')
    } catch {
      /* 用户选择稍后执行 */
    }
  }
}

async function removeKb() {
  await ElMessageBox.confirm('删除知识库将同时删除全部文档与向量数据，确认？', '确认', { type: 'warning' })
  stopPolling()
  await kbApi.remove(currentKbId.value)
  currentKbId.value = ''
  currentKb.value = null
  documents.value = []
  await load()
}

// ==================== 工具 ====================

function methodLabel(method?: string) {
  return chunkMethods.find((m) => m.value === (method || 'general'))?.label || '通用'
}

/** 知识库绑定的向量模型名：空绑定 = 旧版全局配置/平台默认 */
function embeddingModelName(kb?: AiKnowledgeBase | null) {
  const bound = kb?.embeddingModelId ? embeddingModels.value.find((m) => m.id === kb.embeddingModelId) : undefined
  if (bound) return bound.name
  return (vectorStatus.value?.defaultEmbeddingModel as string) || '默认（未绑定）'
}

/** 知识库绑定的重排模型名：未绑定返回空（不展示，不重排） */
function rerankModelName(kb?: AiKnowledgeBase | null) {
  const bound = kb?.rerankModelId ? rerankModels.value.find((m) => m.id === kb.rerankModelId) : undefined
  return bound?.name || ''
}
function statusText(status: number) {
  return ['待处理', '处理中', '完成', '失败'][status] || '未知'
}
function statusType(status: number) {
  return (['info', 'warning', 'success', 'danger'] as const)[status] || 'info'
}
function formatSize(bytes?: number) {
  if (!bytes) return '—'
  return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)}MB` : `${(bytes / 1024).toFixed(0)}KB`
}
</script>

<style scoped>
.page {
  padding: 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.kb-layout {
  display: flex;
  gap: 12px;
  min-height: 400px;
}
.kb-list {
  width: 220px;
  border-right: 1px solid var(--el-border-color-light);
  padding-right: 12px;
}
.kb-item {
  padding: 8px;
  border-radius: 4px;
  cursor: pointer;
  margin-bottom: 4px;
}
.kb-item:hover {
  background: var(--el-fill-color-light);
}
.kb-item.active {
  background: var(--el-color-primary-light-9);
}
.kb-name {
  font-size: 13px;
  font-weight: 600;
}
.kb-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kb-detail {
  flex: 1;
  min-width: 0;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.detail-title {
  font-size: 15px;
  font-weight: 600;
}
.detail-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.search-box {
  padding-top: 4px;
}
.search-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 8px 0 4px;
}
.hit {
  margin-top: 8px;
  padding: 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
.hit-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.hit-content {
  font-size: 12px;
  white-space: pre-wrap;
  max-height: 140px;
  overflow: auto;
}
.form-tip {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.chunk-enhance {
  padding: 4px 12px;
  font-size: 12px;
}
.enhance-line {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 2px 0;
  color: var(--el-text-color-regular);
}
.enhance-line .lbl {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}
.enhance-line.none {
  color: var(--el-text-color-secondary);
}
.kw-tag {
  margin-right: 4px;
}
.q-list {
  line-height: 1.7;
}
</style>
