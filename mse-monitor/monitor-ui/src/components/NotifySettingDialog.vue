<template>
  <el-dialog :model-value="modelValue" title="告警通知设置" width="560px" destroy-on-close @update:model-value="emit('update:modelValue', $event)" @open="load">
    <el-form :model="form" label-width="110px" v-loading="loading">
      <el-divider content-position="left">站内消息</el-divider>
      <el-form-item label="启用站内信">
        <el-switch v-model="form.inboxEnabled" />
      </el-form-item>
      <el-form-item v-if="form.inboxEnabled" label="收件人" required>
        <el-select
          v-model="selectedUserIds"
          multiple
          filterable
          remote
          clearable
          reserve-keyword
          placeholder="输入用户名/昵称搜索"
          :remote-method="searchOptions"
          :loading="searching"
          style="width: 100%"
        >
          <el-option v-for="u in options" :key="u.id" :label="optionLabel(u)" :value="u.id" />
        </el-select>
      </el-form-item>

      <el-divider content-position="left">Webhook 群机器人</el-divider>
      <el-form-item label="启用 Webhook">
        <el-switch v-model="form.webhookEnabled" />
      </el-form-item>
      <template v-if="form.webhookEnabled">
        <el-form-item label="机器人地址" required>
          <el-input v-model="form.webhookUrl" placeholder="https://oapi.dingtalk.com/robot/send?access_token=…" />
        </el-form-item>
        <el-form-item label="报文格式">
          <el-radio-group v-model="form.webhookFormat">
            <el-radio-button value="dingtalk">钉钉</el-radio-button>
            <el-radio-button value="wecom">企业微信</el-radio-button>
            <el-radio-button value="feishu">飞书</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </template>

      <el-divider content-position="left">通知频率</el-divider>
      <el-form-item label="重复通知间隔">
        <el-input-number v-model="form.repeatNotifyMinutes" :min="0" :max="1440" :step="10" controls-position="right" />
        <span class="muted" style="margin-left: 8px">分钟（0=仅首次通知）</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :loading="testing" @click="test">发送测试</el-button>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getNotifySetting, saveNotifySetting, searchUsers, testNotify, type NotifyUserItem } from '../api/monitor'

interface UserOption {
  id: number
  username: string
  nickname?: string
}

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const searching = ref(false)
const options = ref<UserOption[]>([])
const selectedUserIds = ref<number[]>([])
const nameMap = ref<Map<number, string>>(new Map())

const form = reactive({
  inboxEnabled: true,
  inboxUsers: [] as NotifyUserItem[],
  webhookEnabled: false,
  webhookUrl: '',
  webhookFormat: 'dingtalk',
  repeatNotifyMinutes: 0
})

const optionLabel = (u: UserOption) => (u.nickname ? `${u.nickname}（${u.username}）` : u.username)

function syncSelected() {
  form.inboxUsers = selectedUserIds.value
    .map((id) => ({ id, name: nameMap.value.get(id) || String(id) }))
}

async function searchOptions(keyword: string) {
  searching.value = true
  try {
    const users = await searchUsers(keyword || '')
    for (const u of users) {
      nameMap.value.set(u.id, optionLabel(u))
    }
    // 已选项不在本次结果中时保留（避免回显丢失）
    const known = new Set(users.map((u) => u.id))
    const kept = options.value.filter((o) => selectedUserIds.value.includes(o.id) && !known.has(o.id))
    options.value = [...kept, ...users]
  } finally {
    searching.value = false
  }
}

async function load() {
  loading.value = true
  try {
    const setting = await getNotifySetting()
    Object.assign(form, setting)
    nameMap.value = new Map((setting.inboxUsers || []).map((u) => [u.id, u.name]))
    selectedUserIds.value = (setting.inboxUsers || []).map((u) => u.id)
    // 已选用户转为选项回显
    const selected = (setting.inboxUsers || []).map((u) => ({ id: u.id, username: u.name, nickname: undefined }))
    options.value = selected
    await searchOptions('')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (form.inboxEnabled && !selectedUserIds.value.length) {
    ElMessage.warning('请选择至少一名收件人')
    return
  }
  if (form.webhookEnabled && !form.webhookUrl.trim()) {
    ElMessage.warning('请填写 Webhook 机器人地址')
    return
  }
  syncSelected()
  saving.value = true
  try {
    await saveNotifySetting({ ...form, inboxUsers: form.inboxUsers } as Parameters<typeof saveNotifySetting>[0])
    ElMessage.success('通知设置已保存')
    emit('update:modelValue', false)
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  try {
    syncSelected()
    await saveNotifySetting({ ...form } as Parameters<typeof saveNotifySetting>[0])
    const result = await testNotify()
    const text = Object.entries(result)
      .map(([channel, state]) => `${channel === 'inbox' ? '站内信' : 'Webhook'}：${state === 'ok' ? '发送成功' : state}`)
      .join('；')
    if (!text) {
      ElMessage.info('未启用任何通知通道')
    } else if (Object.values(result).every((v) => v === 'ok')) {
      ElMessage.success(text)
    } else {
      ElMessage.warning(text)
    }
  } finally {
    testing.value = false
  }
}
</script>

<style scoped>
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
