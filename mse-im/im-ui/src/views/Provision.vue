<template>
  <div class="page">
    <el-card shadow="never">
      <template #header><span>IM 实例开通与生命周期（平台托管）</span></template>
      <el-form inline>
        <el-form-item label="租户编号">
          <el-select
            v-model="tenantId"
            filterable
            placeholder="选择租户"
            :loading="tenantLoading"
            style="width: 300px"
            @visible-change="onTenantDropdown"
          >
            <el-option
              v-for="t in tenantOptions"
              :key="t.id"
              :label="`${t.name || t.code || t.id}（ID ${t.id}）`"
              :value="t.id"
            >
              <div class="tenant-opt">
                <span>{{ t.name || t.code || t.id }}</span>
                <span class="tenant-opt-meta">ID {{ t.id }} · {{ t.code }}{{ t.status !== 0 ? ' · 已停用' : '' }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="busy" @click="run('provision')">开通</el-button>
          <el-button :loading="busy" @click="run('status')">探活</el-button>
          <el-button :loading="busy" @click="run('stop')">停栈</el-button>
          <el-button :loading="busy" @click="run('start')">启动</el-button>
          <el-button :loading="busy" @click="run('backup')">备份</el-button>
          <el-button :loading="busy" @click="run('nginx')">nginx 配置</el-button>
          <el-popconfirm title="销毁不可逆（down -v 含数据卷 + 删台账），确认？" @confirm="run('destroy')">
            <template #reference>
              <el-button type="danger" :loading="busy">销毁</el-button>
            </template>
          </el-popconfirm>
        </el-form-item>
        <el-form-item label="升级镜像 tag">
          <el-input v-model="imageTag" placeholder="如 v3.8.3-patch.16" style="width: 180px" />
          <el-button :loading="busy" style="margin-left: 8px" @click="run('upgrade')">升级</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="message" :type="messageType" :title="message" :closable="false" />
    </el-card>

    <el-card v-if="result" shadow="never">
      <template #header><span>执行结果</span></template>
      <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
    </el-card>

    <el-card shadow="never">
      <template #header><span>说明</span></template>
      <p class="hint">
        开通 = 按租户渲染独立 compose 并起一套完整 OpenIM 栈（端口块动态分配、随机凭据），成功后写
        PLATFORM_HOSTED 台账并立即对客户端生效；停栈 = 台账停用（客户端报「已停用」）；销毁 = down -v
        含数据卷并物理删台账，不可逆。备份产出 mongo archive 与 minio 数据卷 tar 至
        <code>mse.im.provision.backup-dir</code>。公网坐标由
        <code>mse.im.provision.domain-template</code> 子域模板生成，未配置时为本机端口直连。
      </p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listProvisionTenants, provisionBackup, provisionDestroy, provisionInstance, provisionNginx,
  provisionStart, provisionStatus, provisionStop, provisionUpgrade,
  type ProvisionResult, type ProvisionTenantOption
} from '../api/im'

const tenantId = ref<number | string>('')
const tenantOptions = ref<ProvisionTenantOption[]>([])
const tenantLoading = ref(false)
const imageTag = ref('')
const busy = ref(false)
const message = ref('')
const messageType = ref<'success' | 'info' | 'error'>('info')
const result = ref<ProvisionResult | Record<string, string> | null>(null)

async function loadTenants() {
  if (tenantLoading.value) return
  tenantLoading.value = true
  try {
    tenantOptions.value = (await listProvisionTenants()) || []
  } catch {
    // 失败提示由请求拦截器统一弹出；下拉展开时会重试
  } finally {
    tenantLoading.value = false
  }
}

onMounted(loadTenants)

/** 首次加载失败后，下拉展开时自动补拉 */
function onTenantDropdown(visible: boolean) {
  if (visible && !tenantOptions.value.length) loadTenants()
}

async function run(action: 'provision' | 'status' | 'stop' | 'start' | 'destroy' | 'backup' | 'upgrade' | 'nginx') {
  if (!tenantId.value) {
    ElMessage.warning('请选择租户')
    return
  }
  busy.value = true
  try {
    const id = tenantId.value
    if (action === 'provision') {
      result.value = await provisionInstance(id)
      message.value = '开通成功'
    } else if (action === 'status') {
      result.value = await provisionStatus(id)
      message.value = '探活完成'
    } else if (action === 'stop') {
      await provisionStop(id)
      message.value = '已停栈（台账停用）'
    } else if (action === 'start') {
      await provisionStart(id)
      message.value = '已启动'
    } else if (action === 'destroy') {
      await provisionDestroy(id)
      message.value = '已销毁（栈 + 台账行）'
    } else if (action === 'backup') {
      result.value = await provisionBackup(id)
      message.value = '备份完成'
    } else if (action === 'upgrade') {
      result.value = await provisionUpgrade(id, imageTag.value)
      message.value = '升级完成'
    } else if (action === 'nginx') {
      const conf = await provisionNginx(id)
      result.value = { nginxConf: String(conf) }
      message.value = '已生成'
    }
    messageType.value = 'success'
  } catch (e: any) {
    message.value = e?.message || '操作失败'
    messageType.value = 'error'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.page {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: 0;
}
.tenant-opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tenant-opt-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.result {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
