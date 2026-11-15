<template>
  <div class="page">
    <el-tabs v-model="tab">
      <el-tab-pane label="开放 API Key" name="key" />
      <el-tab-pane label="MCP Server" name="mcp" />
    </el-tabs>

    <!-- API Key -->
    <template v-if="tab === 'key'">
      <div class="toolbar">
        <el-button v-permission="'ai:apikey:create'" type="primary" @click="createVisible = true">签发密钥</el-button>
      </div>
      <el-table :data="keys" border size="small">
        <el-table-column prop="appId" label="App ID" width="160" />
        <el-table-column prop="keyPrefix" label="Key 前缀" width="130" />
        <el-table-column label="智能体白名单" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ agentNames(row.agentCodes) }}</template>
        </el-table-column>
        <el-table-column prop="ratePerMinute" label="限流/分" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'ai:apikey:create'" link type="primary" @click="toggle(row)">
              {{ row.status === 0 ? '停用' : '启用' }}
            </el-button>
            <el-button v-permission="'ai:apikey:create'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="keyTotal" v-model:page="keyPageNum" v-model:size="keyPageSize" @change="load" />
    </template>

    <!-- MCP Server -->
    <template v-else>
      <div class="toolbar">
        <el-button v-permission="'ai:mcp:save'" type="primary" @click="openMcp()">注册 MCP Server</el-button>
      </div>
      <el-table :data="servers" border size="small">
        <el-table-column prop="name" label="服务名" width="140" />
        <el-table-column prop="endpoint" label="端点" min-width="200" show-overflow-tooltip />
        <el-table-column prop="ssePath" label="SSE 路径" width="110" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
              {{ row.status === 0 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="'ai:mcp:save'" link type="primary" @click="testMcp(row)">测试</el-button>
            <el-button v-permission="'ai:mcp:save'" link type="primary" @click="openMcp(row)">编辑</el-button>
            <el-button v-permission="'ai:mcp:save'" link type="danger" @click="removeMcp(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="mcpTotal" v-model:page="mcpPageNum" v-model:size="mcpPageSize" @change="load" />
    </template>

    <!-- 签发密钥 -->
    <el-dialog v-model="createVisible" title="签发开放 API Key" width="480px" @open="loadOptions">
      <el-form :model="keyForm" label-width="110px">
        <el-form-item label="智能体白名单">
          <el-select v-model="selectedAgentCodes" multiple filterable collapse-tags collapse-tags-tooltip
                     style="width: 100%" placeholder="空 = 全部已发布智能体可调用">
            <el-option v-for="a in publishedAgents" :key="a.agentCode"
                       :label="`${a.name}（${a.agentCode}）`" :value="a.agentCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="限流/分钟">
          <el-input-number v-model="keyForm.ratePerMinute" :min="0" placeholder="0=不限" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="keyForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="createKey">签发</el-button>
      </template>
    </el-dialog>

    <!-- 签发结果（明文只显示一次） -->
    <el-dialog v-model="issuedVisible" title="签发成功（请立即保存，明文仅显示一次）" width="520px" :close-on-click-modal="false">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="App ID">{{ issued.appId }}</el-descriptions-item>
        <el-descriptions-item label="API Key">
          <el-text type="danger" tag="b">{{ issued.apiKey }}</el-text>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert type="warning" :closable="false" style="margin-top: 8px"
                title="调用方式：请求头携带 X-App-Id 与 X-Api-Key，POST /openapi/ai/chat" />
      <template #footer>
        <el-button type="primary" @click="issuedVisible = false">我已保存</el-button>
      </template>
    </el-dialog>

    <!-- MCP 编辑 -->
    <el-dialog v-model="mcpVisible" :title="mcpForm.id ? '编辑 MCP Server' : '注册 MCP Server'" width="480px">
      <el-form :model="mcpForm" label-width="100px">
        <el-form-item label="服务名" required>
          <el-input v-model="mcpForm.name" placeholder="工具编码前缀 mcp:{name}:{tool}" />
        </el-form-item>
        <el-form-item label="端点" required>
          <el-input v-model="mcpForm.endpoint" placeholder="http://host:port" />
        </el-form-item>
        <el-form-item label="SSE 路径">
          <el-input v-model="mcpForm.ssePath" placeholder="/sse" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="mcpForm.status">
            <el-radio :value="0">启用</el-radio>
            <el-radio :value="1">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mcpVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMcp">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { agentApi, apiKeyApi, mcpApi } from '../api/ai'
import type { AiAgent, AiApiKey, AiMcpServer } from '../api/ai'
import { Pagination } from 'mse-ui-shared/pc'

const tab = ref('key')
const keys = ref<AiApiKey[]>([])
const servers = ref<AiMcpServer[]>([])
const keyTotal = ref(0)
const keyPageNum = ref(1)
const keyPageSize = ref(20)
const mcpTotal = ref(0)
const mcpPageNum = ref(1)
const mcpPageSize = ref(20)
const createVisible = ref(false)
const keyForm = ref<any>({})
const issuedVisible = ref(false)
const issued = ref<any>({})
const mcpVisible = ref(false)
const mcpForm = ref<any>({})

/** 智能体下拉数据（加载失败置空仅影响选项与名称回显，不阻塞页面） */
const publishedAgents = ref<AiAgent[]>([])
const selectedAgentCodes = ref<string[]>([])

onMounted(() => {
  load()
  loadOptions()
})

async function load() {
  const keyBody: any = await apiKeyApi.page({ pageNum: keyPageNum.value, pageSize: keyPageSize.value })
  keys.value = keyBody?.records ?? []
  keyTotal.value = Number(keyBody?.total ?? 0)
  const mcpBody: any = await mcpApi.page({ pageNum: mcpPageNum.value, pageSize: mcpPageSize.value }).catch(() => null)
  servers.value = mcpBody?.records ?? []
  mcpTotal.value = Number(mcpBody?.total ?? 0)
}

/** 签发弹窗打开时拉取下拉选项 */
async function loadOptions() {
  keyForm.value = {}
  selectedAgentCodes.value = []
  const agentBody: any = await agentApi.options().catch(() => null)
  publishedAgents.value = (agentBody ?? []).filter((a: AiAgent) => a.status === 0)
}

/** CSV agentCode 串映射为「名称（编码）」，未知编码原样保留 */
function agentNames(csv?: string) {
  if (!csv) return '全部'
  return csv.split(',').map((code) => {
    const c = code.trim()
    const a = publishedAgents.value.find((x) => x.agentCode === c)
    return a ? a.name : c
  }).join('、')
}

async function createKey() {
  // 归属强制为会话租户（后端按会话租户落库）
  issued.value = await apiKeyApi.create({
    ...keyForm.value,
    agentCodes: selectedAgentCodes.value.join(',')
  })
  createVisible.value = false
  issuedVisible.value = true
  await load()
}

async function toggle(row: AiApiKey) {
  await apiKeyApi.status(row.id, row.status === 0 ? 1 : 0)
  await load()
}

async function remove(row: AiApiKey) {
  await ElMessageBox.confirm(`删除 ${row.appId}？第三方调用将立即失效`, '确认', { type: 'warning' })
  await apiKeyApi.remove(row.id)
  await load()
}

function openMcp(row?: AiMcpServer) {
  mcpForm.value = row ? { ...row } : { status: 0, transport: 'sse', ssePath: '/sse' }
  mcpVisible.value = true
}

async function saveMcp() {
  if (!mcpForm.value.name || !mcpForm.value.endpoint) {
    ElMessage.warning('服务名与端点必填')
    return
  }
  await mcpApi.save(mcpForm.value)
  ElMessage.success('已保存')
  mcpVisible.value = false
  await load()
}

async function testMcp(row: AiMcpServer) {
  try {
    const tools: any = await mcpApi.test(row.name)
    ElMessageBox.alert((tools ?? []).join('、') || '（无工具）', `连接成功，远端工具 ${tools?.length ?? 0} 个`)
  } catch {
    /* 拦截器已提示 */
  }
}

async function removeMcp(row: AiMcpServer) {
  await ElMessageBox.confirm(`删除 MCP Server「${row.name}」？`, '确认', { type: 'warning' })
  await mcpApi.remove(row.id)
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
</style>
