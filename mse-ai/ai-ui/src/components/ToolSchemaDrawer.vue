<template>
  <el-drawer
    :model-value="visible"
    title="图形化编辑参数"
    size="720px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="hint">
      定义模型调用该工具时可传入的参数，保存后自动生成 JSON Schema。<b>参数说明必填</b>——这是模型理解
      参数用途与取值方式的唯一依据，缺失时模型无法正确传参。
      支持一层基础类型；数组/对象等复杂结构请在「JSON 源码」中维护，应用时原样保留（说明仍必填）。
    </div>
    <div class="toolbar">
      <el-button type="primary" size="small" @click="addRow">添加参数</el-button>
    </div>
    <el-table :data="rows" border size="small">
      <el-table-column label="参数名" width="150">
        <template #default="{ row }">
          <el-input v-model="row.name" size="small" placeholder="如 action" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-select v-model="row.type" size="small">
            <el-option v-for="(label, val) in TYPE_LABELS" :key="val" :label="label" :value="val" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="必填" width="60" align="center">
        <template #default="{ row }">
          <el-checkbox v-model="row.required" />
        </template>
      </el-table-column>
      <el-table-column width="130">
        <template #header>
          <span class="required-mark">说明</span>（模型看）
        </template>
        <template #default="{ row }">
          <el-input v-model="row.description" size="small" placeholder="必填：用途与取值说明" />
        </template>
      </el-table-column>
      <el-table-column label="枚举值（逗号分隔）" min-width="170">
        <template #default="{ row }">
          <el-input v-if="row.type === 'enum'" v-model="row.enumText" size="small" placeholder="如 meta_search,execute" />
          <span v-else class="na">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" fixed="right">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="removeRow($index)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-collapse class="preview">
      <el-collapse-item name="json">
        <template #title>Schema JSON 预览（应用后写回工具）</template>
        <pre class="json-preview">{{ preview }}</pre>
      </el-collapse-item>
    </el-collapse>

    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" @click="apply">应用到工具</el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

/** 图形化参数 Schema 编辑：properties 一层基础类型（string/integer/number/boolean/enum），
 *  array/object 等复杂结构仅展示类型并原样保留原片段，避免图形化编辑丢失嵌套定义。 */
const TYPE_LABELS: Record<string, string> = {
  string: '字符串', integer: '整数', number: '数字', boolean: '布尔',
  enum: '枚举', array: '数组', object: '对象'
}

interface ParamRow {
  name: string
  type: string
  required: boolean
  description: string
  enumText: string
  /** array/object 行的原片段（应用时原样回写） */
  raw?: any
}

const props = defineProps<{ visible: boolean; schema: string }>()
const emit = defineEmits<{ (e: 'update:visible', v: boolean): void; (e: 'apply', schemaJson: string): void }>()

const rows = ref<ParamRow[]>([])

watch(() => props.visible, (open) => {
  if (open) rows.value = parseSchema(props.schema)
})

function parseSchema(schemaJson: string): ParamRow[] {
  const result: ParamRow[] = []
  try {
    const schema = JSON.parse(schemaJson || '{}')
    const required: string[] = Array.isArray(schema?.required) ? schema.required : []
    for (const [name, def] of Object.entries(schema?.properties ?? {})) {
      const d: any = def || {}
      const isEnum = Array.isArray(d.enum)
      // 基础类型 + 枚举可图形化重建；其余（object/array/无 type/自定义关键字）保留原片段防丢定义
      const plain = !isEnum && (d.type === 'string' || d.type === 'integer' || d.type === 'number' || d.type === 'boolean')
      result.push({
        name,
        type: isEnum ? 'enum' : (TYPE_LABELS[d.type] ? d.type : 'object'),
        required: required.includes(name),
        description: d.description ?? '',
        enumText: isEnum ? d.enum.join(',') : '',
        raw: plain || isEnum ? undefined : d
      })
    }
  } catch {
    ElMessage.warning('现有 Schema JSON 解析失败，已给出空表；请用「JSON 源码」修正后再试')
  }
  return result
}

/** silent=true 供实时预览（不弹警告），仅 apply 时提示校验错误 */
function buildSchema(silent: boolean): string | null {
  const seen = new Set<string>()
  const properties: Record<string, any> = {}
  const required: string[] = []
  for (const row of rows.value) {
    const name = row.name.trim()
    if (!name) {
      if (!silent) ElMessage.warning('参数名不能为空')
      return null
    }
    if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name)) {
      if (!silent) ElMessage.warning(`参数名「${name}」需以字母开头，仅含字母/数字/下划线`)
      return null
    }
    if (seen.has(name)) {
      if (!silent) ElMessage.warning(`参数名重复：${name}`)
      return null
    }
    seen.add(name)
    if (row.raw) {
      // 复杂结构原样保留（参数名以当前输入为准），说明仍必填
      if (!row.description.trim()) {
        if (!silent) ElMessage.warning(`参数「${name}」缺少说明：模型无法识别该参数用途`)
        return null
      }
      properties[name] = { ...(row.raw as Record<string, any>), description: row.description.trim() }
      if (row.required) required.push(name)
      continue
    }
    if (!row.description.trim()) {
      if (!silent) ElMessage.warning(`参数「${name}」缺少说明：模型无法识别该参数用途`)
      return null
    }
    if (row.type === 'enum' && !row.enumText.trim()) {
      if (!silent) ElMessage.warning(`枚举参数「${name}」至少需要一个枚举值`)
      return null
    }
    const def: any = { type: row.type === 'enum' ? 'string' : row.type, description: row.description.trim() }
    if (row.type === 'enum') {
      def.enum = row.enumText.split(',').map((s) => s.trim()).filter(Boolean)
    }
    properties[name] = def
    if (row.required) required.push(name)
  }
  const schema: any = { type: 'object', properties }
  if (required.length) schema.required = required
  return JSON.stringify(schema)
}

const preview = computed(() => {
  const json = buildSchema(true)
  if (json === null) return '// 参数定义未完成（缺失参数名/说明/枚举值等），修正后自动生成'
  return JSON.stringify(JSON.parse(json), null, 2)
})

function addRow() {
  rows.value.push({ name: '', type: 'string', required: false, description: '', enumText: '' })
}

function removeRow(index: number) {
  rows.value.splice(index, 1)
}

function apply() {
  const json = buildSchema(false)
  if (json === null) return
  emit('apply', json)
  emit('update:visible', false)
}
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
  line-height: 1.6;
}
.required-mark::before {
  content: '*';
  margin-right: 2px;
  color: var(--el-color-danger);
}
.toolbar {
  margin-bottom: 8px;
}
.na {
  color: var(--el-text-color-placeholder);
}
.preview {
  margin-top: 12px;
}
.json-preview {
  margin: 0;
  padding: 8px;
  font-size: 12px;
  line-height: 1.5;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  max-height: 260px;
  overflow: auto;
}
</style>
