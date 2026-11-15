<template>
  <div class="page">
    <!-- 推测试文档 -->
    <el-card shadow="never">
      <template #header>推送测试文档（直通索引，无业务接入时验证链路）</template>
      <el-form :model="docForm" label-width="110px" inline>
        <el-form-item label="源标识">
          <el-select v-model="docForm.sourceCode" filterable placeholder="选择已登记源" style="width: 220px">
            <el-option v-for="s in options.sources" :key="s.sourceCode" :value="s.sourceCode"
              :label="`${s.sourceCode}（${s.sourceName}）${s.status === 0 ? '' : ' [停用]'}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务主键"><el-input v-model="docForm.bizId" placeholder="1001" style="width: 120px" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="docForm.title" style="width: 240px" /></el-form-item>
        <el-form-item label="归属人">
          <el-select v-model="docForm.ownerUserId" filterable clearable placeholder="选择用户" style="width: 180px">
            <el-option v-for="u in options.users" :key="u.id" :value="u.id"
              :label="`${u.nickname || u.username}（${u.username}）${u.status === 0 ? '' : ' [停用]'}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属部门">
          <el-tree-select v-model="docForm.deptId" :data="options.deptTree" filterable clearable check-strictly
            :props="{ label: 'name', value: 'id', children: 'children' }" node-key="id"
            placeholder="选择部门" style="width: 180px" />
        </el-form-item>
        <el-form-item label="可见类型">
          <el-select v-model="docForm.visibleType" style="width: 130px">
            <el-option label="默认（范围过滤）" value="" />
            <el-option label="公开（all）" value="all" />
          </el-select>
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="docForm.content" type="textarea" :rows="2" style="width: 480px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="pushDoc">推送 / 覆盖</el-button>
          <el-button type="danger" plain @click="pushDelete">删除文档</el-button>
        </el-form-item>
      </el-form>
      <div class="hint">推送前请先在「源管理」登记同名源（源标识下拉自动加载）；归属人/部门为当前租户域，用于验证范围过滤：仅本人→按归属人、本部门→按部门并集</div>
    </el-card>

    <!-- 调试查询 -->
    <el-card shadow="never" style="margin-top: 12px">
      <template #header>调试查询（可忽略个人数据过滤做对比）</template>
      <div class="toolbar">
        <el-input v-model="queryKeyword" placeholder="关键词" clearable style="width: 240px" @keyup.enter="runQuery" />
        <el-checkbox v-model="ignoreDataScope">忽略个人数据过滤</el-checkbox>
        <el-button type="primary" @click="runQuery">搜索</el-button>
      </div>

      <el-table v-if="result.groups?.length" :data="result.groups" size="small" border class="groups">
        <el-table-column prop="sourceCode" label="来源" min-width="160" />
        <el-table-column prop="sourceName" label="名称" min-width="120" />
        <el-table-column prop="count" label="命中" width="90" align="center" />
      </el-table>

      <el-table :data="result.records" size="small" border v-loading="querying">
        <el-table-column label="标题" min-width="220">
          <template #default="{ row }"><span v-html="sanitizeSearchHighlight(row.title)" /></template>
        </el-table-column>
        <el-table-column label="摘要" min-width="300">
          <template #default="{ row }"><span v-html="sanitizeSearchHighlight(row.content)" /></template>
        </el-table-column>
        <el-table-column prop="sourceName" label="来源" width="110" />
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column prop="jumpUrl" label="跳转" min-width="160" show-overflow-tooltip />
      </el-table>
      <div class="hint">共 {{ result.total }} 条（当前过滤视角下）；切换「忽略个人数据过滤」对比命中数即可验证范围过滤</div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { sanitizeSearchHighlight } from 'mse-ui-shared'
import request from '../api/request'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
/** 调试归属域 = 当前登录租户（后端同样按会话租户锁定，平台域调试由平台端另行处理） */
const currentTenantId = () => (auth.tenantId === '' || auth.tenantId === null ? 0 : Number(auth.tenantId))

const docForm = reactive<any>({
  sourceCode: '',
  bizId: '',
  tenantId: currentTenantId(),
  title: '',
  content: '',
  ownerUserId: undefined,
  deptId: undefined,
  visibleType: ''
})
const queryKeyword = ref('')
const ignoreDataScope = ref(false)
const querying = ref(false)
const result = ref<any>({ total: 0, records: [], groups: [] })

const options = ref<any>({ sources: [], tenants: [], users: [], deptTree: [] })

async function loadOptions() {
  // 用户/部门按会话租户域加载（后端锁定），源列表随请求一并刷新
  options.value = await request.get('/search/debug/options')
}

async function pushDoc() {
  if (!docForm.sourceCode || !docForm.bizId) {
    ElMessage.warning('源标识与业务主键必填')
    return
  }
  await request.post('/search/debug/push-doc', { ...docForm, deleted: false })
  ElMessage.success('已推送')
}

async function pushDelete() {
  if (!docForm.sourceCode || !docForm.bizId) {
    ElMessage.warning('源标识与业务主键必填')
    return
  }
  await request.post('/search/debug/push-doc', { ...docForm, deleted: true })
  ElMessage.success('已删除')
}

async function runQuery() {
  if (!queryKeyword.value.trim()) return
  querying.value = true
  try {
    result.value = await request.post('/search/debug/query', {
      keyword: queryKeyword.value,
      tenantId: currentTenantId(),
      ignoreDataScope: ignoreDataScope.value
    })
  } finally {
    querying.value = false
  }
}

onMounted(() => loadOptions().catch(() => {}))
</script>

<style scoped>
.page {
  padding: 12px;
}
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 10px;
  align-items: center;
}
.groups {
  margin-bottom: 10px;
  width: 100%;
}
.hint {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
