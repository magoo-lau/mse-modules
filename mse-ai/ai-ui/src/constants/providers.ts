/**
 * 供应商预设：选中供应商自动带出内置端点与接口类型（端点仍可手改）。
 *
 * 接口类型与后端 ai_model.protocol 对应：
 * - openai-chat：OpenAI 兼容 Chat Completions（SDK 在 baseUrl 后追加 /chat/completions），
 *   覆盖 OpenAI 官方 / DeepSeek / 智谱 GLM / Kimi / 通义千问 / 讯飞星火 / Ollama 及一切兼容端点；
 * - anthropic-messages：Claude 原生 Messages（追加 /v1/messages），覆盖 Anthropic 官方
 *   及 DeepSeek/GLM/Kimi 官方提供的 Anthropic 兼容端点。
 *
 * OpenAI Responses API 暂不支持：Spring AI 2.0.1 尚未实现（上游 issue #2962），
 * protocol 按开放字符串预留，待上游支持后扩展。
 */
export type Protocol = 'openai-chat' | 'anthropic-messages'

export const PROTOCOL_LABELS: Record<Protocol, string> = {
  'openai-chat': 'OpenAI 兼容',
  'anthropic-messages': 'Claude 接口'
}

export interface ProviderEndpoint {
  url: string
  protocol: Protocol
}

export interface ProviderPreset {
  code: string
  label: string
  endpoints: ProviderEndpoint[]
  chatModelPlaceholder?: string
  embeddingModelPlaceholder?: string
  rerankModelPlaceholder?: string
  keyPlaceholder?: string
}

export const providerPresets: ProviderPreset[] = [
  {
    code: 'deepseek',
    label: 'DeepSeek',
    endpoints: [
      { url: 'https://api.deepseek.com', protocol: 'openai-chat' },
      { url: 'https://api.deepseek.com/anthropic', protocol: 'anthropic-messages' }
    ],
    chatModelPlaceholder: 'deepseek-chat / deepseek-reasoner',
    keyPlaceholder: 'sk-...'
  },
  {
    code: 'zhipu',
    label: '智谱 GLM',
    endpoints: [
      { url: 'https://open.bigmodel.cn/api/paas/v4', protocol: 'openai-chat' },
      { url: 'https://open.bigmodel.cn/api/anthropic', protocol: 'anthropic-messages' }
    ],
    chatModelPlaceholder: 'glm-4.6 / glm-4.5 / glm-4-flash',
    embeddingModelPlaceholder: 'embedding-3（向量维度 2048）',
    keyPlaceholder: '...'
  },
  {
    code: 'moonshot',
    label: 'Kimi (Moonshot)',
    endpoints: [
      { url: 'https://api.moonshot.cn/v1', protocol: 'openai-chat' },
      { url: 'https://api.moonshot.cn/anthropic', protocol: 'anthropic-messages' }
    ],
    chatModelPlaceholder: 'kimi-k2-0905-preview / moonshot-v1-8k',
    keyPlaceholder: 'sk-...'
  },
  {
    code: 'dashscope',
    label: '通义千问 (DashScope)',
    endpoints: [{ url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', protocol: 'openai-chat' }],
    chatModelPlaceholder: 'qwen-max / qwen-plus',
    embeddingModelPlaceholder: 'text-embedding-v4',
    keyPlaceholder: 'sk-...'
  },
  {
    code: 'openai',
    label: 'OpenAI 官方',
    endpoints: [{ url: 'https://api.openai.com/v1', protocol: 'openai-chat' }],
    chatModelPlaceholder: 'gpt-4o / gpt-4o-mini',
    embeddingModelPlaceholder: 'text-embedding-3-small（向量维度 1024/1536）',
    keyPlaceholder: 'sk-...'
  },
  {
    code: 'anthropic',
    label: 'Anthropic Claude',
    endpoints: [{ url: 'https://api.anthropic.com', protocol: 'anthropic-messages' }],
    chatModelPlaceholder: 'claude-sonnet-4-5 / claude-haiku-4-5',
    keyPlaceholder: 'sk-ant-...（无向量接口，RAG 需配独立 OpenAI 兼容向量端点）'
  },
  {
    code: 'ollama',
    label: 'Ollama 本地',
    endpoints: [{ url: 'http://localhost:11434/v1', protocol: 'openai-chat' }],
    chatModelPlaceholder: 'qwen3:14b / llama3.1',
    embeddingModelPlaceholder: 'nomic-embed-text（向量维度 768）',
    keyPlaceholder: 'ollama（本地可填任意占位）'
  },
  {
    code: 'xfyun',
    label: '讯飞星火',
    endpoints: [{ url: 'https://spark-api-open.xf-yun.com/v1', protocol: 'openai-chat' }],
    chatModelPlaceholder: '4.0Ultra / x2 / x1 / max-32k / pro-128k / lite',
    embeddingModelPlaceholder: '星火向量服务非 OpenAI 兼容，RAG 需另配独立向量端点',
    keyPlaceholder: 'APIPassword（星火控制台获取，非 APPID/APISecret）'
  },
  {
    code: 'openai-compat',
    label: '自定义 OpenAI 兼容',
    endpoints: [],
    chatModelPlaceholder: '按端点文档填写模型名',
    keyPlaceholder: '...'
  }
]

export function findPreset(code?: string): ProviderPreset | undefined {
  return providerPresets.find((p) => p.code === code)
}

export function providerLabel(code?: string): string {
  return findPreset(code)?.label ?? code ?? ''
}

export function protocolLabel(protocol?: string): string {
  return PROTOCOL_LABELS[protocol as Protocol] ?? protocol ?? ''
}
