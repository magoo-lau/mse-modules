/**
 * 系统内置智能体图标（emoji 精选集）。
 *
 * <p>头像字段（ai_agent.avatar）与既有数据格式完全兼容：内置图标存 emoji 字面量，
 * 本地上传存站内/外图片 URL；前端按 http(s)/站内路径前缀识别为图片，否则按 emoji 文本渲染。
 */
export const AGENT_AVATAR_GROUPS: { label: string; emojis: string[] }[] = [
  {
    label: '通用',
    emojis: ['🤖', '🧠', '💡', '⚡', '✨', '🌟', '🎯', '🧩', '🔧', '🛠️', '⚙️', '🔑']
  },
  {
    label: '数据',
    emojis: ['📊', '📈', '📉', '🗂️', '🗃️', '🧮', '🔍', '🔎', '📋', '📅', '⏱️', '🗄️']
  },
  {
    label: '办公',
    emojis: ['📝', '✍️', '📄', '📮', '📬', '🗓️', '✅', '🖊️', '📚', '🏷️', '🧾', '📎']
  },
  {
    label: '沟通',
    emojis: ['💬', '🗣️', '📢', '📧', '☎️', '🤝', '👥', '🧑‍💼', '🙋', '👋', '🔔', '🛎️']
  },
  {
    label: '业务',
    emojis: ['🧭', '🚀', '🛡️', '⚖️', '💰', '🛒', '🏭', '🧪', '🔬', '🌐', '🧬', '🏗️']
  }
]

/** 全部内置图标平铺（判「是否内置图标」用） */
export const AGENT_AVATARS = AGENT_AVATAR_GROUPS.flatMap((g) => g.emojis)
