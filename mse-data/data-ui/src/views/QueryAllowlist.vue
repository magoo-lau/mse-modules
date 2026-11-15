<template>
  <el-card shadow="never">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="表名" clearable style="width: 180px" @keyup.enter="load" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 110px">
        <el-option label="启用" :value="0" />
        <el-option label="停用" :value="1" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button v-permission="'data:allow:save'" type="primary" plain @click="openCreate">新增白名单表</el-button>
    </div>

    <el-table :data="rows" size="small" v-loading="loading">
      <el-table-column prop="tableName" label="表名" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 0 ? 'success' : 'info'" size="small">
            {{ row.status === 0 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
      <el-table-column prop="createTime" label="创建时间" width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="'data:allow:save'" link type="primary" size="small" @click="openEdit(row)">编辑
          </el-button>
          <el-button v-permission="'data:allow:save'" link size="small" @click="toggle(row)">
            {{ row.status === 0 ? '停用' : '启用' }}
          </el-button>
          <el-button v-permission="'data:allow:delete'" link type="danger" size="small" @click="remove(row)">删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />
  </el-card>

  <el-dialog v-model="dialog" :title="form.id ? '编辑白名单表' : '新增白名单表'" width="520px">
    <el-alert type="info" :closable="false" show-icon
      title="白名单内的业务表允许经中台库通道（datasourceId=0）被 AI 工具/数据服务查询，查询时自动按租户过滤（表须含 tenant_id 列）；ods_ 贴源表无需登记。"
      style="margin-bottom: 12px" />
    <el-form :model="form" label-width="90px">
      <el-form-item label="表名" required>
        <el-input v-model="form.tableName" placeholder="如：oa_leave_request（自动转小写）" />
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
const emptyForm = { id: 0, tableName: '', remark: '' }
const form = reactive<any>({ ...emptyForm })

async function load() {
  loading.value = true
  try {
    const data: any = await request.get('/data/allow/page', { params: query })
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
  Object.assign(form, emptyForm, row, { id: row.id })
  dialog.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await request.put('/data/allow', form)
    } else {
      await request.post('/data/allow', form)
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

async function toggle(row: any) {
  await request.post(`/data/allow/${row.id}/status`, null, { params: { enable: row.status !== 0 } })
  load()
}

async function remove(row: any) {
  await ElMessageBox.confirm(`确认将表「${row.tableName}」移出查询白名单？移出后中台库通道立即拒绝查询该表。`, '删除确认', { type: 'warning' })
  await request.delete(`/data/allow/${row.id}`)
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
</style>
