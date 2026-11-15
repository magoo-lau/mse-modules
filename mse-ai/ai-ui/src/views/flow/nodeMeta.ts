/**
 * 编排节点元数据（mse-flow/v1）：面板分组、图标、默认配置、输出变量清单。
 * 与后端 vip.mseapp.ai.workflow 包的 NodeExecutor 一一对应。
 */

export interface NodeOutputVar {
  name: string
  label: string
}

export interface NodeMeta {
  type: string
  label: string
  icon: string
  color: string
  group: '基础' | '转换' | '数据与工具' | '分支循环'
  /** 属性面板是否含"上游变量"模板字段 */
  templateFields?: { key: string; label: string; rows?: number; placeholder?: string }[]
  /** 节点输出变量（变量引用提示用） */
  outputs: NodeOutputVar[]
  /** 连接桩：输出桩清单（路由节点多桩，sourceHandle 对应） */
  handles?: { id: string; label: string }[]
}

export const NODE_METAS: NodeMeta[] = [
  {
    type: 'start', label: '开始', icon: '▶', color: '#409eff', group: '基础',
    outputs: [
      { name: 'query', label: '用户输入 sys.query' },
      { name: 'conversation', label: '会话历史 sys.conversation' },
      { name: '(自定义入参)', label: '引用 {{开始节点id.入参名}}，见上游变量列表' }
    ]
  },
  {
    type: 'llm', label: 'LLM', icon: '🧠', color: '#626aef', group: '基础',
    templateFields: [
      { key: 'systemPrompt', label: '系统提示词', rows: 3, placeholder: '可选，角色设定' },
      { key: 'prompt', label: '提示词', rows: 4, placeholder: '支持 {{nodeId.var}} 变量引用' }
    ],
    outputs: [{ name: 'text', label: '回复文本' }]
  },
  {
    type: 'answer', label: '直接回复', icon: '💬', color: '#67c23a', group: '基础',
    templateFields: [{ key: 'content', label: '回复内容', rows: 3, placeholder: '如：分析结果：{{llm1.text}}' }],
    outputs: [{ name: 'text', label: '回复文本' }]
  },
  {
    type: 'end', label: '结束', icon: '⏹', color: '#909399', group: '基础',
    outputs: [{ name: 'output', label: '结构化输出' }]
  },
  {
    type: 'knowledge', label: '知识检索', icon: '📚', color: '#e6a23c', group: '数据与工具',
    templateFields: [{ key: 'query', label: '检索问题', rows: 2, placeholder: '默认 {{sys.query}}' }],
    outputs: [
      { name: 'result', label: '召回拼文' },
      { name: 'chunks', label: '命中切片数组' }
    ]
  },
  {
    type: 'tool', label: '工具', icon: '🔧', color: '#f56c6c', group: '数据与工具',
    outputs: [
      { name: 'text', label: '工具返回文本' },
      { name: 'json', label: 'JSON（可解析时）' }
    ]
  },
  {
    type: 'dataset', label: '数据查询', icon: '📊', color: '#0d9488', group: '数据与工具',
    templateFields: [
      { key: 'sql', label: '查询 SQL', rows: 4, placeholder: '只读 SELECT，支持 {{nodeId.var}} 条件' }
    ],
    outputs: [
      { name: 'text', label: '结果文本（JSON）' },
      { name: 'columns', label: '列名数组' },
      { name: 'rows', label: '行数组' },
      { name: 'markdown', label: '表格文本（供 LLM 阅读）' },
      { name: 'rowCount', label: '行数' }
    ]
  },
  {
    type: 'condition', label: '条件分支', icon: '🔀', color: '#b8860b', group: '分支循环',
    outputs: [{ name: 'matched', label: '命中分支' }],
    handles: [{ id: 'case0', label: 'IF' }, { id: 'default', label: 'ELSE' }]
  },
  {
    type: 'classifier', label: '问题分类', icon: '🏷️', color: '#c71585', group: '分支循环',
    templateFields: [{ key: 'query', label: '分类文本', rows: 2, placeholder: '默认 {{sys.query}}' }],
    outputs: [
      { name: 'class_name', label: '命中分类名' },
      { name: 'class_id', label: '命中分类序号' }
    ]
  },
  {
    type: 'extractor', label: '参数提取', icon: '📐', color: '#9966cc', group: '转换',
    templateFields: [{ key: 'query', label: '抽取来源', rows: 2, placeholder: '默认 {{sys.query}}' }],
    outputs: [{ name: '(字段)', label: '按配置字段输出' }]
  },
  {
    type: 'code', label: '代码执行', icon: '⚙️', color: '#545c64', group: '转换',
    outputs: [{ name: '(返回键)', label: 'main(args) 返回对象的各键' }]
  },
  {
    type: 'http', label: 'HTTP 请求', icon: '🌐', color: '#3a8ee6', group: '转换',
    templateFields: [{ key: 'body', label: '请求体', rows: 3, placeholder: 'POST/PUT 时发送，支持变量' }],
    outputs: [
      { name: 'status_code', label: '状态码' },
      { name: 'headers', label: '响应头' },
      { name: 'body', label: '响应文本' },
      { name: 'json(.字段)', label: 'JSON 可解析时，支持 {{id.json.子字段}} 取子字段' }
    ]
  },
  {
    type: 'template', label: '模板转换', icon: '🧩', color: '#d4a017', group: '转换',
    templateFields: [{ key: 'template', label: 'Jinja2 模板', rows: 4, placeholder: '{{ llm1.text }}' }],
    outputs: [{ name: 'output', label: '渲染结果' }]
  },
  {
    type: 'assign', label: '变量赋值', icon: '✏️', color: '#2f9e8f', group: '转换',
    templateFields: [],
    outputs: [{ name: 'assigned', label: '本次赋值的全局参数名清单' }]
  },
  {
    type: 'aggregator', label: '变量聚合', icon: '🖇️', color: '#8f9aa8', group: '转换',
    outputs: [{ name: 'output', label: '首个非空变量' }]
  },
  {
    type: 'iteration', label: '迭代', icon: '🔁', color: '#7b5cd6', group: '分支循环',
    outputs: [{ name: 'output', label: '逐项输出数组' }]
  }
]

const META_BY_TYPE = new Map(NODE_METAS.map((m) => [m.type, m]))

export function metaOf(type: string): NodeMeta {
  return (
    META_BY_TYPE.get(type) || {
      type, label: type, icon: '▪', color: '#909399', group: '转换', outputs: []
    }
  )
}

/**
 * 节点可被下游引用的输出变量：静态清单 + 配置动态生成项（开始入参/提取字段）。
 * 静态清单中 `(xxx)` 为占位提示（如 code 的返回键），不出现在可选列表。
 * FlowCard 节点卡片展示与 FlowEditor 上游变量分组共用本函数。
 */
export function nodeOutputVars(dsl: { type: string; data?: Record<string, any> } | null | undefined): NodeOutputVar[] {
  if (!dsl) return []
  const data = dsl.data || {}
  const vars = metaOf(dsl.type).outputs.filter((v) => !v.name.startsWith('('))
  if (dsl.type === 'start') {
    ;(data.variables || []).forEach((v: any) => v?.name && vars.push({ name: v.name, label: '入参' }))
  }
  if (dsl.type === 'extractor') {
    ;(data.fields || []).forEach((f: any) => f?.name && vars.push({ name: f.name, label: f.description || '提取字段' }))
  }
  return vars
}

/** 新节点默认配置（与后端执行器键约定一致） */
export function defaultData(type: string): Record<string, any> {
  switch (type) {
    case 'start':
      return { variables: [] }
    case 'llm':
      return { prompt: '{{sys.query}}', systemPrompt: '', temperature: null, memoryEnabled: true, windowSize: 10, passthrough: true, modelId: null }
    case 'knowledge':
      return { query: '{{sys.query}}', kbIds: '', topK: 4, minScore: null }
    case 'tool':
      return { toolCode: '', args: {} }
    case 'dataset':
      return { sql: '', datasourceId: 0, rowLimit: 200, toolCode: '' }
    case 'condition':
      return {
        cases: [
          {
            logic: 'and',
            conditions: [{ left: '{{sys.query}}', op: 'not_empty', right: '' }]
          }
        ]
      }
    case 'classifier':
      return {
        query: '{{sys.query}}', modelId: null,
        classes: [
          { name: '分类一', description: '' },
          { name: '分类二', description: '' }
        ]
      }
    case 'extractor':
      return {
        query: '{{sys.query}}', modelId: null,
        fields: [{ name: 'keyword', type: 'string', description: '要抽取的关键字' }]
      }
    case 'code':
      return {
        code: [
          '// 输入：args（开始节点变量/上游引用）',
          '// 返回对象，各键成为输出变量',
          'function main(args) {',
          "  return { text: String(args.item ?? '') }",
          '}'
        ].join('\n'),
        args: {}
      }
    case 'http':
      return { method: 'GET', url: '', headers: {}, body: '', timeout: 15000 }
    case 'template':
      return { template: '' }
    case 'assign':
      return { assignments: [] }
    case 'aggregator':
      return { variables: [] }
    case 'iteration':
      return { items: '', outputSelector: '', maxItems: 50, graph: null }
    case 'answer':
      return { content: '{{sys.query}}' }
    case 'end':
      return { outputs: {} }
    default:
      return {}
  }
}

/** 条件运算符选项（与 ConditionNodeExecutor 对齐） */
export const CONDITION_OPS = [
  { value: 'contains', label: '包含' },
  { value: 'not_contains', label: '不包含' },
  { value: 'eq', label: '等于' },
  { value: 'ne', label: '不等于' },
  { value: 'starts_with', label: '前缀' },
  { value: 'ends_with', label: '后缀' },
  { value: 'gt', label: '大于' },
  { value: 'ge', label: '大于等于' },
  { value: 'lt', label: '小于' },
  { value: 'le', label: '小于等于' },
  { value: 'empty', label: '为空' },
  { value: 'not_empty', label: '不为空' },
  { value: 'regex', label: '正则匹配' }
]

/** 全局参数类型选项（与后端 VarTypes 对齐） */
export const GLOBAL_TYPES = [
  { value: 'string', label: '文本' },
  { value: 'number', label: '数值' },
  { value: 'boolean', label: '布尔' },
  { value: 'array', label: '集合' },
  { value: 'object', label: '对象' }
]

export function globalTypeLabel(type?: string): string {
  return GLOBAL_TYPES.find((t) => t.value === type)?.label || type || '文本'
}

/** 变量赋值操作选项（与 AssignNodeExecutor 对齐；types 为空表示全部类型可用） */
export const ASSIGN_OPS = [
  { value: 'set', label: '覆盖', types: [] as string[] },
  { value: 'reset', label: '重置', types: [] as string[] },
  { value: 'append', label: '追加', types: ['string', 'array'] },
  { value: 'add', label: '累加', types: ['number'] },
  { value: 'subtract', label: '累减', types: ['number'] },
  { value: 'merge', label: '合并', types: ['array', 'object'] }
]

/** 按目标参数声明类型过滤可用操作（未声明类型的参数放开全部操作） */
export function assignOpsForType(type?: string) {
  return ASSIGN_OPS.filter((op) => !type || !op.types.length || op.types.includes(type))
}
