import request from './request'

/** IM 实例台账（每租户一行；secret 密文不回显） */
export interface ImInstance {
  id?: number
  source?: string
  apiUrl?: string
  wsUrl?: string
  publicApiUrl?: string
  publicWsUrl?: string
  webBaseUrl?: string
  adminUserId?: string
  status?: number
  lastTestTime?: string
  lastTestOk?: number
  lastTestMsg?: string
}

/** 实例配置保存请求（secret 留空 = 保持不变；status 0 启用 1 停用） */
export interface ImInstanceSaveRequest {
  apiUrl?: string
  wsUrl?: string
  publicApiUrl?: string
  publicWsUrl?: string
  webBaseUrl?: string
  adminUserId?: string
  secret?: string
  status?: number
}

/** IM 机器人绑定（bot 账号 ↔ mse-ai 智能体） */
export interface ImBot {
  id?: number
  botUserId?: string
  nickname?: string
  faceUrl?: string
  agentCode?: string
  triggerKeywords?: string
  groupIds?: string
  status?: number
}

// -------------------- 实例配置 --------------------

export const getInstance = () => request.get<Record<string, any>>('/im/instance')

export const saveInstance = (data: ImInstanceSaveRequest) => request.put('/im/instance', data)

export const testInstance = () => request.post<{ ok: boolean; message: string }>('/im/instance/test')

// -------------------- 机器人 --------------------

export const listBots = () => request.get<ImBot[]>('/im/bot')

export const saveBot = (data: ImBot) => request.post<ImBot>('/im/bot', data)

export const deleteBot = (id: number) => request.delete(`/im/bot/${id}`)

/** 绑定智能体下拉选项（本世界已发布智能体；avatar 为 emoji 字面量或站内/外图片 URL） */
export interface AgentOption {
  agentCode: string
  name?: string
  description?: string
  scope?: string
  avatar?: string
}

export const listAgentOptions = () => request.get<AgentOption[]>('/im/bot/agent-options')

/** 上传机器人头像素材（≤2MB，png/jpg/jpeg/gif/webp），返回可写入 faceUrl 的站内地址 */
export const uploadBotAsset = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return request.post<string>('/im/bot/asset', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// -------------------- 平台端：IM 开通与生命周期（im:provision:manage） --------------------

export interface ProvisionResult {
  tenantId: number | string
  apiPort?: number
  wsPort?: number
  projectId?: string
  callbackToken?: string
  publicApiUrl?: string
  publicWsUrl?: string
  nginxConf?: string
  image?: string
  alive?: boolean
  status?: number
  apiUrl?: string
  lastTestOk?: number
  lastTestMsg?: string
}

/** 平台端 IM 开通：租户下拉选项（sys_tenant 全量含停用，平台 0 号行除外；status 0 启用 1 停用） */
export interface ProvisionTenantOption {
  id: number
  code?: string
  name?: string
  status?: number
}

export const listProvisionTenants = () => request.get<ProvisionTenantOption[]>('/im/provision/tenants')

export const provisionInstance = (tenantId: string | number) =>
  request.post<ProvisionResult>(`/im/provision/${tenantId}`)

export const provisionStatus = (tenantId: string | number) =>
  request.get<ProvisionResult>(`/im/provision/${tenantId}`)

export const provisionStop = (tenantId: string | number) => request.post(`/im/provision/${tenantId}/stop`)

export const provisionStart = (tenantId: string | number) => request.post(`/im/provision/${tenantId}/start`)

export const provisionDestroy = (tenantId: string | number) => request.delete(`/im/provision/${tenantId}`)

export const provisionUpgrade = (tenantId: string | number, imageTag: string) =>
  request.post<ProvisionResult>(`/im/provision/${tenantId}/upgrade`, { imageTag })

export const provisionBackup = (tenantId: string | number) =>
  request.post<Record<string, string>>(`/im/provision/${tenantId}/backup`)

export const provisionNginx = (tenantId: string | number) => request.get<string>(`/im/provision/${tenantId}/nginx`)

// -------------------- 部门群（群主=部门负责人，成员随部门名单对账收敛） --------------------

export interface DeptGroupRow {
  deptId: number
  deptName?: string
  groupId: string
  groupName?: string
  ownerUserId?: number
  ownerName?: string
  memberCount?: number
}

export interface DeptGroupSyncSummary {
  groupsCreated: number
  groupsDismissed: number
  membersAdded: number
  membersKicked: number
  ownersTransferred: number
  groupsRenamed: number
}

export const listDeptGroups = () => request.get<DeptGroupRow[]>('/im/dept-group')

export const syncDeptGroups = () => request.post<DeptGroupSyncSummary>('/im/dept-group/sync')

// -------------------- 工作台配置（聊天客户端宫格入口） --------------------

/** 工作台入口（entryType 0=mse 页面菜单 1=外部应用；openMode 0=内嵌 1=弹窗；authMode 0=直连 1=免登授权；status 0=启用 1=停用） */
export interface WorkbenchItem {
  id?: number | string
  entryType?: number
  path: string
  title?: string
  icon?: string
  /** 宫格分组名（空=聊天客户端按菜单树目录推导；外部应用缺省归「外部应用」） */
  groupName?: string
  openMode?: number
  authMode?: number
  /** 免登授权关联的开放平台应用（authMode=1 必填，凭证归口开放平台） */
  openAppId?: number | string
  /** 可见角色编号集合（保存传数组；读取为逗号分隔字符串；空=全员可见） */
  roleIds?: number[] | string
  sort?: number
  status?: number
}

/** 开放平台应用下拉选项（免登授权入口关联候选；status 0=启用 1=停用） */
export interface OpenAppOption {
  id: number | string
  appKey: string
  name: string
  tenantId: number | string
  status: number
}

/** 工作台视图（客户端拉取）：跳转基址 + 入口项 */
export interface WorkbenchVO {
  webBaseUrl: string
  items: WorkbenchItem[]
}

export interface WorkbenchMenuItem { path: string; name: string }

/** 本租户 type=3 页面菜单候选（经 /system/profile/me，与聊天客户端同源） */
export const listWorkbenchMenus = () => request.get<Record<string, any>>('/system/profile/me')

/** 拉取工作台入口（all=true 返回含停用行，需 im:workbench:query） */
export const getWorkbench = (all = false) => request.get<WorkbenchVO>(`/im/workbench${all ? '?all=true' : ''}`)

export const saveWorkbench = (items: WorkbenchItem[]) => request.put<void>('/im/workbench', { items })

/** 本租户开放平台应用清单（免登授权入口下拉候选，含停用行） */
export const listWorkbenchOpenApps = () => request.get<OpenAppOption[]>('/im/workbench/open-apps')

/** 角色下拉选项（入口可见角色候选） */
export interface RoleOption { id: number | string; name: string }

/** 本租户启用角色清单（入口「可见角色」下拉候选） */
export const listWorkbenchRoles = () => request.get<RoleOption[]>('/im/workbench/roles')

/** 上传工作台入口图标（≤2MB，png/jpg/jpeg/gif/webp），返回站内地址 */
export const uploadWorkbenchIcon = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return request.post<string>('/im/workbench/asset', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
