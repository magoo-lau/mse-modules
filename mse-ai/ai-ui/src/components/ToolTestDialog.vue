<template>
  <el-dialog
    :model-value="visible"
    :title="`测试 ${tool?.toolCode ?? ''}`"
    width="780px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:visible', $event)"
  >
    <template v-if="tool">
      <div class="tool-desc">
        <el-tag size="small" effect="plain">{{ tool.name || tool.toolCode }}</el-tag>
        <span class="desc-text">{{ tool.description || '（无功能说明）' }}</span>
      </div>

      <div class="mode-bar">
        <el-radio-group v-model="mode" size="small">
          <el-radio-button value="form">表单填写</el-radio-button>
          <el-radio-button value="json">JSON 源码</el-radio-button>
        </el-radio-group>
        <span class="mode-hint">
          {{ mode === 'form' ? '按参数逐项填写值（说明/类型/必填如下），仅必填项不可为空' : '完整入参 JSON，可直接粘贴；两种模式共用一份值' }}
        </span>
      </div>

      <template v-if="mode === 'form'">
        <el-table v-if="rows.length" :data="rows" border size="small" max-height="360">
          <el-table-column label="参数名" width="140">
            <template #default="{ row }">
              <span :class="{ required: row.required }">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" type="info">{{ typeLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="必填" width="60" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.required" size="small" type="danger" effect="plain">必填</el-tag>
              <span v-else class="optional">可选</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="param-desc">{{ row.description || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="值" min-width="200">
            <template #default="{ row }">
              <el-select
                v-if="row.type === 'enum'"
                v-model="row.value"
                size="small"
                clearable
                placeholder="不传"
              >
                <el-option v-for="opt in row.enumOptions" :key="String(opt)" :label="String(opt)" :value="opt" />
              </el-select>
              <el-select
                v-else-if="row.type === 'boolean'"
                v-model="row.value"
                size="small"
                clearable
                placeholder="不传"
              >
                <el-option label="true" :value="true" />
                <el-option label="false" :value="false" />
              </el-select>
              <el-input-number
                v-else-if="row.type === 'integer'"
                v-model="row.value"
                size="small"
                controls-position="right"
                step-strictly
                :value-on-clear="null"
                placeholder="不传"
                class="full-width"
              />
              <el-input-number
                v-else-if="row.type === 'number'"
                v-model="row.value"
                size="small"
                controls-position="right"
                :value-on-clear="null"
                placeholder="不传"
                class="full-width"
              />
              <el-input
                v-else-if="row.type === 'array' || row.type === 'object'"
                v-model="row.value"
                type="textarea"
                :rows="2"
                size="small"
                :placeholder="row.type === 'array' ? 'JSON 数组，如 [&quot;a&quot;]' : 'JSON 对象，如 {&quot;k&quot;:1}'"
              />
              <el-input v-else v-model="row.value" size="small" placeholder="不传则留空" />
            </template>
          </el-table-column>
        </el-table>
        <div v-else class="no-params">该工具未定义参数（无参调用），可直接切到 JSON 源码粘贴或保持 {} 调用。</div>
      </template>

      <el-input
        v-else
        v-model="jsonText"
        type="textarea"
        :rows="10"
        spellcheck="false"
        placeholder='{"action":"meta_search","keyword":"订单"}'
        class="json-editor"
      />

      <div v-if="result" class="result-block">
        <div class="result-head">
          <span class="result-label">调用结果</span>
          <el-tag :type="result.success ? 'success' : 'danger'" size="small">
            {{ result.success ? '成功' : '失败' }}
          </el-tag>
        </div>
        <pre class="result-content">{{ prettyContent }}</pre>
      </div>
    </template>

    <template #footer>
      <el-button @click="emit('update:visible', false)">关闭</el-button>
      <el-button type="primary" :loading="calling" @click="call">调用</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { toolApi } from '../api/ai'
import type { AiTool } from '../api/ai'

/** 工具测试弹窗：按参数 Schema 双模式填写入参——表单模式逐项填值（展示说明/类型/必填）、
 *  JSON 模式直接粘贴整包入参；两模式切换时同步值，调用走 /ai/tool/{code}/test 并就地展示结果。 */

interface ParamValueRow {
  name: string
  type: string
  required: boolean
  description: string
  enumOptions?: any[]
  /** 表单值：基础类型为直接值，array/object 为 textarea 中的 JSON 文本 */
  value: any
}

const props = defineProps<{ visible: boolean; tool: AiTool | null }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void }>()

const mode = ref<'form' | 'json'>('form')
const rows = ref<ParamValueRow[]>([])
const jsonText = ref('{}')
const calling = ref(false)
const result = ref<{ success: boolean; content: string } | null>(null)

const TYPE_LABELS: Record<string, string> = {
  string: '字符串', integer: '整数', number: '数字', boolean: '布尔', array: '数组', object: '对象'
}

function typeLabel(row: ParamValueRow) {
  return row.type === 'enum' ? '枚举' : TYPE_LABELS[row.type] || row.type
}

watch(() => props.visible, (open) => {
  if (!open) return
  result.value = null
  calling.value = false
  rows.value = parseSchema(props.tool?.parametersJsonSchema)
  mode.value = rows.value.length ? 'form' : 'json'
  jsonText.value = '{}'
})

/** 解析工具 Schema 为可填写行（解析失败给出空表并提示走 JSON 模式） */
function parseSchema(schemaJson?: string): ParamValueRow[] {
  const result: ParamValueRow[] = []
  if (!schemaJson) return result
  try {
    const schema = JSON.parse(schemaJson)
    const required: string[] = Array.isArray(schema?.required) ? schema.required : []
    for (const [name, def] of Object.entries(schema?.properties ?? {})) {
      const d: any = def || {}
      const isEnum = Array.isArray(d.enum)
      result.push({
        name,
        type: isEnum ? 'enum' : (TYPE_LABELS[d.type] ? d.type : 'object'),
        required: required.includes(name),
        description: d.description ?? '',
        enumOptions: isEnum ? d.enum : undefined,
        value: d.type === 'array' || d.type === 'object' ? '' : undefined
      })
    }
  } catch {
    ElMessage.warning('参数 Schema 解析失败，请用 JSON 源码模式填写')
  }
  return result
}

function isEmpty(row: ParamValueRow) {
  return row.value === undefined || row.value === null || (typeof row.value === 'string' && !row.value.trim())
}

/** 表单值 → 入参 JSON；strict=true 时校验必填与 JSON 合法性 */
function buildArgs(strict: boolean): { json?: string; error?: string } {
  const args: Record<string, any> = {}
  for (const row of rows.value) {
    const empty = isEmpty(row)
    if (empty) {
      if (strict && row.required) return { error: `必填参数「${row.name}」未填值` }
      continue
    }
    if (row.type === 'array' || row.type === 'object') {
      try {
        args[row.name] = JSON.parse(row.value)
      } catch {
        if (strict) return { error: `参数「${row.name}」不是合法 JSON` }
        continue
      }
    } else {
      args[row.name] = row.value
    }
  }
  return { json: JSON.stringify(args) }
}

/** 模式切换双向同步：表单→JSON 全量序列化；JSON→表单尽力回填（失败回退并保留原 JSON） */
let revertSync = false
watch(mode, (next, prev) => {
  if (revertSync) {
    revertSync = false
    return
  }
  if (!props.visible) return
  if (next === 'json') {
    const built = buildArgs(false)
    try {
      jsonText.value = built.json ? JSON.stringify(JSON.parse(built.json), null, 2) : '{}'
    } catch {
      jsonText.value = '{}'
    }
    return
  }
  try {
    const parsed = JSON.parse(jsonText.value || '{}')
    const map: Record<string, any> = parsed && typeof parsed === 'object' ? parsed : {}
    rows.value.forEach((row) => {
      if (!(row.name in map)) return
      const v = map[row.name]
      if (row.type === 'array' || row.type === 'object') {
        row.value = JSON.stringify(v, null, 2)
      } else {
        row.value = v
      }
    })
  } catch {
    ElMessage.warning('JSON 解析失败，已停留在 JSON 源码模式')
    revertSync = true
    mode.value = 'json'
  }
})

const prettyContent = computed(() => {
  const content = result.value?.content ?? ''
  try {
    return JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    return content
  }
})

async function call() {
  if (!props.tool) return
  let argsJson: string
  if (mode.value === 'json') {
    const raw = jsonText.value.trim() || '{}'
    try {
      JSON.parse(raw)
    } catch {
      ElMessage.warning('入参 JSON 格式有误，请检查')
      return
    }
    argsJson = raw
  } else {
    const built = buildArgs(true)
    if (built.error) {
      ElMessage.warning(built.error)
      return
    }
    argsJson = built.json || '{}'
  }
  calling.value = true
  result.value = null
  try {
    const res: any = await toolApi.test(props.tool.toolCode, argsJson)
    result.value = { success: res?.success !== false, content: res?.content ?? '' }
  } catch {
    // HTTP/权限错误已由拦截器统一提示
  } finally {
    calling.value = false
  }
}
</script>

<style scoped>
.tool-desc {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 10px;
}
.desc-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.mode-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.mode-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.required::before {
  content: '*';
  margin-right: 2px;
  color: var(--el-color-danger);
}
.optional {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.param-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.no-params {
  padding: 16px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.json-editor :deep(textarea) {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.5;
}
.full-width {
  width: 100%;
}
.result-block {
  margin-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
}
.result-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.result-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.result-content {
  margin: 0;
  padding: 8px;
  font-size: 12px;
  line-height: 1.5;
  font-family: Consolas, Menlo, monospace;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
