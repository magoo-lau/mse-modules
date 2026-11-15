<template>
  <div class="page">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="文件名" clearable style="width: 200px" @keyup.enter="load" />
      <el-input v-model="query.bizType" placeholder="业务分类" clearable style="width: 140px" @keyup.enter="load" />
      <el-date-picker v-model="range" type="daterange" range-separator="-" start-placeholder="开始日期" end-placeholder="结束日期"
                      value-format="YYYY-MM-DD" style="width: 240px" />
      <el-button type="primary" @click="load">查询</el-button>
      <div class="spacer" />
      <el-tag size="small" :type="info.type === 'rustfs' ? 'success' : 'info'">
        {{ info.type === 'rustfs' ? 'RustFS 对象存储' : '本地文件存储' }}
      </el-tag>
      <el-button v-permission="'fs:storage:migrate'" size="small" :loading="migrating" @click="doMigrate">迁移历史文件</el-button>
      <el-upload v-permission="'fs:file:upload'" :show-file-list="false" :http-request="doUpload" multiple>
        <el-button type="primary" :loading="uploading">上传文件</el-button>
      </el-upload>
      <el-upload v-if="info.multipartEnabled" v-permission="'fs:file:upload'" :show-file-list="false"
                 :http-request="doMultipartUpload">
        <el-button :loading="multipartUploading">大文件直传</el-button>
      </el-upload>
    </div>

    <el-table :data="rows" size="small" border v-loading="loading">
      <el-table-column prop="fileName" label="文件名" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="file-name">
            <el-image v-if="thumbs[row.id]" :src="thumbs[row.id]" fit="cover" class="thumb"
                      :preview-src-list="[thumbs[row.id]]" preview-teleported hide-on-click-modal />
            <span>{{ row.fileName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="100" align="right">
        <template #default="{ row }">{{ fmtSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column prop="contentType" label="类型" width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.contentType || '-' }}</template>
      </el-table-column>
      <el-table-column prop="bizType" label="业务分类" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.bizType || '-' }}</template>
      </el-table-column>
      <el-table-column label="存储" width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.uploadId" size="small" type="warning">直传中</el-tag>
          <el-tag v-else size="small" :type="row.storageType === 'rustfs' ? 'success' : 'info'">
            {{ row.storageType === 'rustfs' ? 'RustFS' : '本地' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上传时间" width="160">
        <template #default="{ row }">{{ fmt(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" align="center">
        <template #default="{ row }">
          <el-button v-permission="'fs:file:download'" link type="primary" size="small" @click="doDownload(row)">下载</el-button>
          <el-button v-if="info.presignEnabled" v-permission="'fs:file:download'" link type="primary" size="small"
                     @click="doCopyDirectUrl(row)">直链</el-button>
          <el-button v-permission="'fs:file:delete'" link type="danger" size="small" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <Pagination :total="total" v-model:page="query.pageNum" v-model:size="query.pageSize" @change="load" />

    <el-dialog v-model="progressVisible" title="大文件分片直传" width="380px" :close-on-click-modal="false"
               :show-close="false">
      <div class="progress-name">{{ progressName }}</div>
      <el-progress :percentage="progressPercent" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteFile, downloadFile, fetchThumbnailUrl, isThumbnailSupported, migrateStorage,
  pageFiles, presignUrl, storageInfo, uploadFile, uploadMultipartFile,
  type FsFile, type StorageInfo
} from '../api/fs'
import { copyText } from '../utils/clipboard'
import { Pagination } from 'mse-ui-shared/pc'

const rows = ref<FsFile[]>([])
const total = ref(0)
const loading = ref(false)
const uploading = ref(false)
const multipartUploading = ref(false)
const migrating = ref(false)
const range = ref<[string, string] | null>(null)
const info = reactive<StorageInfo>({ type: '-', presignEnabled: false, multipartEnabled: false })
/** 行缩略图（objectURL，img 带不了登录头故经 axios 拉取） */
const thumbs = reactive<Record<string, string>>({})
const query = reactive<{ pageNum: number; pageSize: number; keyword: string; bizType: string }>({
  pageNum: 1, pageSize: 10, keyword: '', bizType: ''
})
const progressVisible = ref(false)
const progressName = ref('')
const progressPercent = ref(0)

function fmt(time: string | null) {
  return (time || '').replace('T', ' ').slice(0, 19)
}

function fmtSize(size: number) {
  if (size == null) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

async function load() {
  loading.value = true
  try {
    const [beginTime, endTime] = range.value || [undefined, undefined]
    const data: any = await pageFiles({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      bizType: query.bizType || undefined,
      beginTime: beginTime ? `${beginTime}T00:00:00` : undefined,
      endTime: endTime ? `${endTime}T23:59:59` : undefined
    })
    rows.value = data.records
    total.value = Number(data.total)
    loadThumbs()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/** 图片行异步补缩略图（失败静默：不阻塞列表） */
function loadThumbs() {
  for (const row of rows.value) {
    if (thumbs[row.id] || !isThumbnailSupported(row)) continue
    fetchThumbnailUrl(row.id, 256)
      .then((url) => { thumbs[row.id] = url })
      .catch(() => { /* 无缩略图降级为纯文件名 */ })
  }
}

async function doUpload(option: any) {
  uploading.value = true
  try {
    await uploadFile(option.file)
    ElMessage.success(`已上传：${option.file.name}`)
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    uploading.value = false
  }
}

async function doMultipartUpload(option: any) {
  const file: File = option.file
  multipartUploading.value = true
  progressVisible.value = true
  progressName.value = file.name
  progressPercent.value = 0
  try {
    await uploadMultipartFile(file, (percent) => { progressPercent.value = percent })
    progressVisible.value = false
    ElMessage.success(`已直传：${file.name}`)
    await load()
  } catch (e: any) {
    progressVisible.value = false
    ElMessage.error(e?.message || '分片直传失败')
  } finally {
    multipartUploading.value = false
  }
}

async function doMigrate() {
  const from = info.type === 'rustfs' ? 'local' : 'rustfs'
  const fromLabel = from === 'local' ? '本地存储' : 'RustFS 对象存储'
  try {
    await ElMessageBox.confirm(
      `将把 ${fromLabel} 中的历史文件对象搬移到当前存储（${from} → ${info.type}），文件较多时耗时较长，建议低峰执行。确认执行？`,
      '存储迁移', { type: 'warning' })
  } catch {
    return
  }
  migrating.value = true
  try {
    const result: any = await migrateStorage(from)
    ElMessage.success(`迁移完成：成功 ${result.migrated}，失败 ${result.failed}，跳过 ${result.skipped}`)
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    migrating.value = false
  }
}

async function doDownload(row: FsFile) {
  try {
    await downloadFile(row)
  } catch {
    /* 拦截器已提示 */
  }
}

async function doCopyDirectUrl(row: FsFile) {
  try {
    const url: string = await presignUrl(row.id)
    const ok = await copyText(url)
    ElMessage[ok ? 'success' : 'error'](ok ? '直链已复制（限时有效）' : '复制失败')
  } catch {
    /* 拦截器已提示 */
  }
}

async function doDelete(row: FsFile) {
  try {
    await ElMessageBox.confirm(`确认删除文件「${row.fileName}」？物理对象将一并删除（秒传共享对象除外）。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteFile(row.id)
    delete thumbs[row.id]
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

onMounted(async () => {
  try {
    Object.assign(info, await storageInfo())
  } catch {
    /* 拦截器已提示 */
  }
  await load()
})
</script>

<style scoped>
.page {
  padding: 12px;
  height: 100%;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  overflow: auto;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
  align-items: center;
}
.spacer {
  flex: 1;
}
.file-name {
  display: flex;
  align-items: center;
  gap: 6px;
}
.thumb {
  width: 28px;
  height: 28px;
  border-radius: 3px;
  flex: none;
  border: 1px solid var(--el-border-color-lighter);
}
.progress-name {
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
.pager {
  margin-top: 10px;
  justify-content: flex-end;
}
</style>
