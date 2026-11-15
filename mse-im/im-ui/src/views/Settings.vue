<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>IM 实例配置</span>
          <el-tag v-if="configured" :type="instance?.status === 0 ? 'success' : 'info'" size="small">
            {{ instance?.status === 0 ? '已启用' : '已停用' }}
          </el-tag>
          <el-tag v-else type="warning" size="small">未配置（登录签发走平台默认实例）</el-tag>
        </div>
      </template>

      <el-form :model="form" label-width="130px" class="instance-form">
        <el-form-item label="服务端调用地址" required>
          <el-input v-model="form.apiUrl" placeholder="http://127.0.0.1:10002（mse → OpenIM admin REST）" />
        </el-form-item>
        <el-form-item label="内网 WS 地址">
          <el-input v-model="form.wsUrl" placeholder="ws://127.0.0.1:10001（可选）" />
        </el-form-item>
        <el-form-item label="公网 apiAddr">
          <el-input v-model="form.publicApiUrl" placeholder="https://im.xxx.com/api（缺省回落服务端调用地址）" />
        </el-form-item>
        <el-form-item label="公网 wsAddr">
          <el-input v-model="form.publicWsUrl" placeholder="wss://im.xxx.com/msg_gateway" />
        </el-form-item>
        <el-form-item label="工作台跳转基址">
          <el-input v-model="form.webBaseUrl" placeholder="http://localhost:3000（聊天客户端工作台免登跳转的 mse Web 端地址；留空用全局缺省）" />
        </el-form-item>
        <el-form-item label="实例管理员账号">
          <el-input v-model="form.adminUserId" placeholder="imAdmin" />
        </el-form-item>
        <el-form-item label="实例 secret">
          <el-input v-model="form.secret" type="password" show-password
                    :placeholder="configured ? '留空保持不变' : '与 OpenIM share.yml secret 一致'" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          <span class="hint">停用 = 本租户 IM 显式关闭（登录签发报「已停用」，不回落平台默认）</span>
        </el-form-item>
        <el-form-item v-if="row?.source === 'PLATFORM_HOSTED'" label="托管说明">
          <el-alert type="info" :closable="false"
                    title="本租户实例由平台托管开通，地址与密钥由平台维护，租户仅可启停与测试" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button :loading="testing" @click="test">连通性测试</el-button>
        </el-form-item>
        <el-form-item v-if="testResult">
          <el-alert :type="testResult.ok ? 'success' : 'error'" :title="testResult.message" :closable="false" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="callbackUrl" shadow="never" class="callback-card">
      <template #header>Webhook 回调（机器人 / 停用踢线的硬依赖）</template>
      <p class="hint">
        将实例 <code>config/webhooks.yml</code> 的 <code>url</code> 配置为以下地址并订阅 after 类事件，
        即可启用停用踢线与机器人能力：
      </p>
      <el-input :model-value="callbackUrl" readonly>
        <template #append>
          <el-button @click="copy">复制</el-button>
        </template>
      </el-input>
      <el-alert style="margin-top: 10px" type="warning" :closable="false"
                title="回调由 OpenIM 实例反向推送到 mse：实例位于内网/NAT 后且网络不可达时，机器人与停用踢线能力受限（连通性测试会标注探测结果）；平台托管实例不受影响" />
    </el-card>

    <el-card shadow="never" class="callback-card">
      <template #header>
        <div class="card-header">
          <span>部门群</span>
          <el-button size="small" type="primary" :loading="syncing" @click="syncGroups">立即同步</el-button>
        </div>
      </template>
      <el-alert type="info" :closable="false" style="margin-bottom: 10px"
                title="按部门自动建群：群主 = 部门负责人（「系统管理 → 部门管理」设置，未设置时为租户管理员），成员随部门在职名单自动同步。用户登录 IM 后自动触发对账（10 分钟防抖），也可在此手动立即同步。" />
      <el-table :data="deptGroups" size="small" border empty-text="暂无部门群——用户登录 IM 或点击「立即同步」后自动创建">
        <el-table-column prop="deptName" label="部门" min-width="130" />
        <el-table-column label="群名" min-width="140">
          <template #default="{ row }">{{ row.groupName || row.groupId }}</template>
        </el-table-column>
        <el-table-column label="群主" width="130">
          <template #default="{ row }">{{ row.ownerName || (row.ownerUserId ?? '-') }}</template>
        </el-table-column>
        <el-table-column prop="memberCount" label="成员数" width="80" align="center" />
      </el-table>
      <el-alert v-if="syncSummary" style="margin-top: 10px" type="success" :closable="false"
                :title="`同步完成：新建 ${syncSummary.groupsCreated} 群、改名 ${syncSummary.groupsRenamed}、转让群主 ${syncSummary.ownersTransferred}、补拉成员 ${syncSummary.membersAdded}、踢出 ${syncSummary.membersKicked}、解散 ${syncSummary.groupsDismissed}`" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getInstance, saveInstance, testInstance, listDeptGroups, syncDeptGroups,
         type ImInstance, type ImInstanceSaveRequest, type DeptGroupRow, type DeptGroupSyncSummary } from '../api/im'

const row = ref<ImInstance | null>(null)
const configured = ref(false)
const callbackUrl = ref('')
const saving = ref(false)
const testing = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)

const form = ref<ImInstanceSaveRequest & { enabled?: boolean }>({})

onMounted(async () => {
  const data: any = await getInstance()
  configured.value = !!data?.configured
  if (data?.configured) {
    row.value = data.instance
    callbackUrl.value = data.callbackUrl || ''
    form.value = { ...data.instance, secret: '' }
    form.value.enabled = data.instance.status === 0
  } else {
    form.value = { enabled: true, adminUserId: 'imAdmin' }
  }
  loadDeptGroups()
})

async function save() {
  if (!form.value.apiUrl) {
    ElMessage.warning('服务端调用地址必填')
    return
  }
  saving.value = true
  try {
    await saveInstance({
      apiUrl: form.value.apiUrl,
      wsUrl: form.value.wsUrl,
      publicApiUrl: form.value.publicApiUrl,
      publicWsUrl: form.value.publicWsUrl,
      adminUserId: form.value.adminUserId || 'imAdmin',
      webBaseUrl: form.value.webBaseUrl,
      secret: form.value.secret,
      status: form.value.enabled ? 0 : 1
    })
    ElMessage.success('已保存')
    const data: any = await getInstance()
    row.value = data.instance
    callbackUrl.value = data.callbackUrl || ''
    configured.value = true
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  try {
    testResult.value = await testInstance()
  } finally {
    testing.value = false
  }
}

// -------------------- 部门群 --------------------

const deptGroups = ref<DeptGroupRow[]>([])
const syncing = ref(false)
const syncSummary = ref<DeptGroupSyncSummary | null>(null)

async function loadDeptGroups() {
  try {
    deptGroups.value = (await listDeptGroups()) || []
  } catch {
    deptGroups.value = [] // 实例未配置等场景静默留空
  }
}

async function syncGroups() {
  syncing.value = true
  try {
    syncSummary.value = await syncDeptGroups()
    ElMessage.success('同步完成')
    await loadDeptGroups()
  } finally {
    syncing.value = false
  }
}

function copy() {
  navigator.clipboard?.writeText(callbackUrl.value)
  ElMessage.success('已复制')
}
</script>

<style scoped>
.page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 860px;
}
.card-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.instance-form {
  max-width: 620px;
}
.hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.callback-card code {
  color: var(--el-color-primary);
}
</style>
