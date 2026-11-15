<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="名称/主机" clearable style="width: 180px" @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px">
        <el-option label="启用" :value="0" />
        <el-option label="停用" :value="1" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button v-permission="'data:source:save'" type="primary" plain @click="openCreate">新增数据源</el-button>
    </div>

    <el-table :data="rows" size="small" v-loading="loading">
      <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="type" label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="地址" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.host }}:{{ row.port }} / {{ row.dbName }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户名" width="110" show-overflow-tooltip />
      <el-table-column label="连通性" width="140">
        <template #default="{ row }">
          <span v-if="row.lastTestOk === 1" class="ok">正常</span>
          <el-tooltip v-else-if="row.lastTestOk === 0" :content="row.lastTestMsg || '失败'">
            <span class="bad">失败</span>
          </el-tooltip>
          <span v-else class="muted">未测试</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
            {{ row.status === 0 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'data:source:save'" link type="primary" size="small" :loading="row._testing"
            @click="test(row)">测试</el-button>
          <el-button v-permission="'data:source:save'" link type="primary" size="small" @click="openEdit(row)">编辑
          </el-button>
          <el-button v-permission="'data:source:save'" link size="small" @click="toggle(row)">
            {{ row.status === 0 ? '停用' : '启用' }}
          </el-button>
          <el-button v-permission="'data:source:delete'" link type="danger" size="small" @click="remove(row)">删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
  </el-card>

  <el-dialog v-model="dialog" :title="form.id ? '编辑数据源' : '新增数据源'" width="560px">
    <el-alert type="info" :closable="false" show-icon
      title="仅允许登记租户自有/外部业务库；平台主库无法登记为外部数据源，中台库取数请在「数据集成」任务来源中选择内置「中台库」。"
      style="margin-bottom: 12px" />
    <el-form :model="form" label-width="90px">
      <el-form-item label="名称" required>
        <el-input v-model="form.name" placeholder="如：业务主库" />
      </el-form-item>
      <el-form-item label="类型" required>
        <el-select v-model="form.type" style="width: 160px">
          <el-option label="MySQL" value="mysql" />
          <el-option label="达梦 DM8" value="dm" />
          <el-option label="金仓 KingbaseES" value="kingbase" />
          <el-option label="PostgreSQL" value="postgresql" />
        </el-select>
      </el-form-item>
      <el-form-item label="主机" required>
        <el-input v-model="form.host" placeholder="127.0.0.1" />
      </el-form-item>
      <el-form-item label="端口" required>
        <el-input-number v-model="form.port" :min="1" :max="65535" />
      </el-form-item>
      <el-form-item label="库/Schema" required>
        <el-input v-model="form.dbName" />
      </el-form-item>
      <el-form-item label="追加参数">
        <el-input v-model="form.extraParams" placeholder="JDBC 追加参数，如 useSSL=false（可空）" />
      </el-form-item>
      <el-form-item label="用户名" required>
        <el-input v-model="form.username" />
      </el-form-item>
      <el-form-item label="口令" :required="!form.id">
        <el-input v-model="form.password" type="password" show-password
          :placeholder="form.id ? '留空表示不修改' : ''" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'
import { Pagination } from 'mse-ui-shared/pc'

const rows = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialog = ref(false)
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', status: undefined as number | undefined })
const emptyForm = { id: 0, name: '', type: 'mysql', host: '', port: 3306, dbName: '', extraParams: '', username: '', password: '', remark: '' }
const form = reactive<any>({ ...emptyForm })

const TYPE_LABELS: Record<string, string> = { mysql: 'MySQL', dm: '达梦', kingbase: '金仓', postgresql: 'PostgreSQL' }
const typeLabel = (t: string) => TYPE_LABELS[t] || t

async function load() {
  loading.value = true
  try {
    const data: any = await request.get('/data/source/page', { params: query })
    rows.value = data.records || []
    total.value = Number(data.total)
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, emptyForm, { id: 0 })
  dialog.value = true
}

function openEdit(row: any) {
  Object.assign(form, emptyForm, row, { id: row.id, password: '' })
  dialog.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await request.put('/data/source', form)
    } else {
      await request.post('/data/source', form)
    }
    ElMessage.success('已保存')
    dialog.value = false
    load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function test(row: any) {
  row._testing = true
  try {
    const result: any = await request.post(`/data/source/${row.id}/test`)
    if (result.ok) {
      ElMessage.success('连接正常')
    } else {
      ElMessage.error('连接失败：' + (result.message || '未知错误'))
    }
    load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    row._testing = false
  }
}

async function toggle(row: any) {
  await request.post(`/data/source/${row.id}/status`, null, { params: { enable: row.status !== 0 } })
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认删除数据源「${row.name}」？使用它的同步任务与 API 将失效。`, '删除确认', { type: 'warning' })
  await request.delete(`/data/source/${row.id}`)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.pager {
  margin-top: 8px;
  justify-content: flex-end;
}
.ok {
  color: var(--el-color-success);
}
.bad {
  color: var(--el-color-danger);
  cursor: help;
}
.muted {
  color: var(--el-text-color-placeholder);
}
</style>
