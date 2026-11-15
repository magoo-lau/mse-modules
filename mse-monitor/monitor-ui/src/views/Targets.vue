<template>
  <div class="page">
    <el-card shadow="never">
      <div class="filter-bar">
        <el-input v-model="query.keyword" placeholder="目标名称" clearable style="width: 180px" @keyup.enter="load" />
        <el-select v-model="query.targetType" placeholder="类型" clearable style="width: 140px">
          <el-option v-for="(label, key) in TYPE_LABELS" :key="key" :label="label" :value="key" />
        </el-select>
        <el-select v-model="query.enabled" placeholder="启停" clearable style="width: 110px">
          <el-option label="启用" :value="0" />
          <el-option label="停用" :value="1" />
        </el-select>
        <el-button type="primary" @click="load">查询</el-button>
        <el-button v-permission="'monitor:target:save'" type="primary" plain @click="openCreate">新增目标</el-button>
      </div>

      <el-table :data="rows" size="small" v-loading="loading" row-key="id">
        <el-table-column prop="name" label="名称" min-width="130" show-overflow-tooltip />
        <el-table-column label="类型" width="90">
          <template #default="{ row }"><el-tag size="small" type="info">{{ TYPE_LABELS[row.targetType] || row.targetType }}</el-tag></template>
        </el-table-column>
        <el-table-column label="地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ addr(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <span class="status-cell">
              <span class="dot" :class="row.status"></span>{{ statusLabel(row.status) }}
              <span v-if="row.status === 'up'" class="muted">{{ row.lastLatencyMs ?? '-' }} ms</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="collectInterval" label="间隔(s)" width="75" />
        <el-table-column label="启用" width="70">
          <template #default="{ row }">
            <el-switch
              v-permission="'monitor:target:save'"
              size="small"
              :model-value="row.enabled === 0"
              @change="(v: string | number | boolean) => toggle(row, !!v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right" class-name="op-col">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="router.push(`/targets/${row.id}`)">详情</el-button>
            <el-button v-permission="'monitor:target:probe'" text type="primary" size="small" :loading="probing === row.id" @click="probe(row)">检测</el-button>
            <el-button v-permission="'monitor:target:save'" text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="'monitor:target:delete'" text type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- 分页 -->
      <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑目标' : '新增目标'" width="560px" destroy-on-close>
      <el-form :model="form" label-width="92px">
        <el-form-item label="目标名称" required>
          <el-input v-model="form.name" placeholder="如：mse-oa / Redis 缓存" />
        </el-form-item>
        <el-form-item label="目标类型" required>
          <el-radio-group v-model="form.targetType" :disabled="!!form.id">
            <el-radio-button v-for="(label, key) in TYPE_LABELS" :key="key" :value="key">{{ label }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.targetType && URL_PLACEHOLDERS[form.targetType]" label="地址" required>
          <el-input v-model="form.serviceUrl" :placeholder="URL_PLACEHOLDERS[form.targetType]" />
        </el-form-item>
        <template v-if="['mysql', 'redis', 'rocketmq'].includes(form.targetType || '')">
          <el-form-item label="主机" required>
            <el-input v-model="form.host" placeholder="IP 或主机名" />
          </el-form-item>
          <el-form-item label="端口" required>
            <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
          </el-form-item>
        </template>
        <el-form-item v-if="form.targetType === 'mysql'" label="用户名">
          <el-input v-model="form.username" placeholder="监控账号（如 root）" />
        </el-form-item>
        <el-form-item v-if="form.targetType === 'mysql' || form.targetType === 'redis'" label="密码">
          <el-input v-model="form.password" type="password" show-password :placeholder="form.id ? '留 **** 表示不变' : ''" />
        </el-form-item>
        <el-form-item label="采集间隔">
          <el-input-number v-model="form.collectInterval" :min="5" :max="86400" controls-position="right" />
          <span class="muted" style="margin-left: 8px">秒</span>
        </el-form-item>
        <el-form-item label="告警规则">
          <div class="alert-rules">
            <el-checkbox v-model="rule.down">探测失败告警</el-checkbox>
            <div class="rule-line"><span>响应耗时超过</span><el-input-number v-model="rule.latencyMs" :min="0" :step="500" controls-position="right" style="width: 120px" /><span>ms（0=不启用）</span></div>
            <div class="rule-line"><span>CPU 使用率超过</span><el-input-number v-model="rule.cpuPercent" :min="0" :max="100" controls-position="right" style="width: 100px" /><span>%（0=不启用）</span></div>
            <div class="rule-line"><span>内存使用率超过</span><el-input-number v-model="rule.memPercent" :min="0" :max="100" controls-position="right" style="width: 100px" /><span>%（0=不启用）</span></div>
            <div class="rule-line"><span>磁盘使用率超过</span><el-input-number v-model="rule.diskPercent" :min="0" :max="100" controls-position="right" style="width: 100px" /><span>%（0=不启用）</span></div>
            <div class="rule-line"><span>Redis 内存超过</span><el-input-number v-model="rule.redisMemPercent" :min="0" :max="100" controls-position="right" style="width: 100px" /><span>%（0=不启用）</span></div>
            <div class="rule-line"><span>Redis 慢命令达到</span><el-input-number v-model="rule.redisSlowCmdCount" :min="0" :step="5" controls-position="right" style="width: 100px" /><span>条（0=不启用，仅慢命令抓取开启时评估）</span></div>
            <div class="rule-line"><span>MySQL 连接数超过</span><el-input-number v-model="rule.mysqlMaxThreads" :min="0" :step="50" controls-position="right" style="width: 120px" /><span>（0=不启用）</span></div>
            <div class="rule-line"><span>JVM 堆内存使用率超过</span><el-input-number v-model="rule.jvmHeapPercent" :min="0" :max="100" controls-position="right" style="width: 100px" /><span>%（0=不启用）</span></div>
            <div class="rule-line"><span>每次采集新增慢SQL超过</span><el-input-number v-model="rule.slowSqlCount" :min="0" :step="5" controls-position="right" style="width: 100px" /><span>条（0=不启用）</span></div>
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  changeTargetStatus, createTarget, deleteTarget, pageTargets, probeTarget, updateTarget,
  type MonTarget
} from '../api/monitor'
import { Pagination } from 'mse-ui-shared/pc'

const router = useRouter()

const TYPE_LABELS: Record<string, string> = {
  service: '微服务', http: 'HTTP', jvm: 'JVM', slowsql: '慢SQL', mysql: 'MySQL', redis: 'Redis', rocketmq: 'RocketMQ', os: '操作系统'
}

const URL_PLACEHOLDERS: Record<string, string> = {
  service: 'http://host:port（自动探测 /actuator/health）',
  jvm: 'http://host:port（自动拉取 /actuator/metrics）',
  slowsql: 'http://host:port（自动拉取 /actuator/slowsql）',
  http: 'http://host:port/path'
}

interface AlertRule {
  down: boolean
  latencyMs: number
  cpuPercent: number
  memPercent: number
  diskPercent: number
  redisMemPercent: number
  mysqlMaxThreads: number
  jvmHeapPercent: number
  slowSqlCount: number
  redisSlowCmdCount: number
}

const query = reactive({ pageNum: 1, pageSize: 20, keyword: '', targetType: '', enabled: undefined as number | undefined })
const rows = ref<MonTarget[]>([])
const total = ref(0)
const loading = ref(false)
const probing = ref<string | number | null>(null)
const dialogVisible = ref(false)
const saving = ref(false)

const form = reactive<Partial<MonTarget>>({})
const rule = reactive<AlertRule>({ down: true, latencyMs: 0, cpuPercent: 0, memPercent: 0, diskPercent: 0, redisMemPercent: 0, mysqlMaxThreads: 0, jvmHeapPercent: 0, slowSqlCount: 0, redisSlowCmdCount: 0 })

const addr = (t: MonTarget) => t.serviceUrl || (t.host ? `${t.host}:${t.port ?? ''}` : '本机')
const statusLabel = (st: string) => (st === 'up' ? '正常' : st === 'down' ? '异常' : '未采集')

async function load() {
  loading.value = true
  try {
    const page = await pageTargets({ ...query })
    rows.value = page.records
    total.value = Number(page.total ?? 0)
  } finally {
    loading.value = false
  }
}

function parseRule(target: Partial<MonTarget>): AlertRule {
  const base: AlertRule = { down: true, latencyMs: 0, cpuPercent: 0, memPercent: 0, diskPercent: 0, redisMemPercent: 0, mysqlMaxThreads: 0, jvmHeapPercent: 0, slowSqlCount: 0, redisSlowCmdCount: 0 }
  if (!target.alertConfig) return base
  try {
    const cfg = JSON.parse(target.alertConfig) as Partial<AlertRule>
    return {
      down: cfg.down !== false,
      latencyMs: cfg.latencyMs ?? 0,
      cpuPercent: cfg.cpuPercent ?? 0,
      memPercent: cfg.memPercent ?? 0,
      diskPercent: cfg.diskPercent ?? 0,
      redisMemPercent: cfg.redisMemPercent ?? 0,
      mysqlMaxThreads: cfg.mysqlMaxThreads ?? 0,
      jvmHeapPercent: cfg.jvmHeapPercent ?? 0,
      slowSqlCount: cfg.slowSqlCount ?? 0,
      redisSlowCmdCount: cfg.redisSlowCmdCount ?? 0
    }
  } catch {
    return base
  }
}

function openCreate() {
  Object.assign(form, {
    id: undefined, name: '', targetType: 'service', serviceUrl: '', host: '', port: undefined,
    username: '', password: '', collectInterval: 30, remark: ''
  })
  Object.assign(rule, parseRule({}))
  dialogVisible.value = true
}

function openEdit(row: MonTarget) {
  Object.assign(form, { ...row })
  Object.assign(rule, parseRule(row))
  dialogVisible.value = true
}

function buildAlertConfig(): string {
  const cfg: Record<string, unknown> = { down: rule.down }
  if (rule.latencyMs > 0) cfg.latencyMs = rule.latencyMs
  if (rule.cpuPercent > 0) cfg.cpuPercent = rule.cpuPercent
  if (rule.memPercent > 0) cfg.memPercent = rule.memPercent
  if (rule.diskPercent > 0) cfg.diskPercent = rule.diskPercent
  if (rule.redisMemPercent > 0) cfg.redisMemPercent = rule.redisMemPercent
  if (rule.mysqlMaxThreads > 0) cfg.mysqlMaxThreads = rule.mysqlMaxThreads
  if (rule.jvmHeapPercent > 0) cfg.jvmHeapPercent = rule.jvmHeapPercent
  if (rule.slowSqlCount > 0) cfg.slowSqlCount = rule.slowSqlCount
  if (rule.redisSlowCmdCount > 0) cfg.redisSlowCmdCount = rule.redisSlowCmdCount
  return JSON.stringify(cfg)
}

async function save() {
  if (!form.name?.trim()) {
    ElMessage.warning('请填写目标名称')
    return
  }
  saving.value = true
  try {
    const payload = { ...form, alertConfig: buildAlertConfig() }
    if (form.id) {
      await updateTarget(payload)
    } else {
      await createTarget(payload)
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(row: MonTarget, enable: boolean) {
  await changeTargetStatus(row.id, enable)
  row.enabled = enable ? 0 : 1
}

async function probe(row: MonTarget) {
  probing.value = row.id
  try {
    const result = await probeTarget(row.id)
    if (result.status === 'up') {
      ElMessage.success(`探测正常，耗时 ${result.latencyMs ?? 0} ms`)
    } else {
      ElMessage.error(`探测失败：${result.error || '未知原因'}`)
    }
    load()
  } finally {
    probing.value = null
  }
}

async function remove(row: MonTarget) {
  await ElMessageBox.confirm(`确认删除监控目标「${row.name}」？其历史采样将一并删除。`, '删除确认', { type: 'warning' })
  await deleteTarget(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.page {
  padding: 12px;
}
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
.status-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot.up {
  background: var(--el-color-success);
}
.dot.down {
  background: var(--el-color-danger);
}
.dot.unknown {
  background: var(--el-text-color-disabled);
}
.muted {
  color: var(--el-text-color-secondary);
}
:deep(.op-col .cell) {
  white-space: nowrap;
}
.alert-rules {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rule-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
