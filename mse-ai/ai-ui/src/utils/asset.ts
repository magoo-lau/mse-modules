/**
 * 站内素材地址解析：后端落库/返回的素材是站内规范路径（/fs/asset/**、
 * /ai/agent/asset/**），img 标签不携带 Authorization，
 * 浏览器加载须经前端 /api 代理（dev proxy 与生产网关/nginx 同约定）；
 * 绝对地址（http(s):）与 data:/blob: 原样返回。
 */
const API_ASSET_PREFIXES = ['/fs/', '/ai/agent/asset/']

export function resolveAssetSrc(url: string | null | undefined): string {
  if (!url) return ''
  return API_ASSET_PREFIXES.some((p) => url.startsWith(p)) ? `/api${url}` : url
}
