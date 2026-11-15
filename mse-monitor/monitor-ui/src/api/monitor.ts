import request from './request'

/** 后端 Long 经 Jackson 序列化为字符串 */
export type Id = string | number

export interface MonTarget {
  id: Id
  name: string
  targetType: string
  serviceUrl?: string
  host?: string
  port?: number
  username?: string
  password?: string
  collectInterval?: number
  enabled: number
  alertConfig?: string
  status: string
  statusTime?: string
  lastLatencyMs?: number | null
  remark?: string
}

export interface MonRecord {
  id: Id
  targetId: Id
  collectTime: string
  status: string
  latencyMs?: number | null
  metrics?: string | null
}

export interface MonAlert {
  id: Id
  targetId: Id
  targetName: string
  level: string
  ruleKey: string
  content?: string
  status: string
  firstTriggerTime?: string
  lastTriggerTime?: string
  resolveTime?: string
  notifyResult?: string
}

export interface NotifyUserItem {
  id: number
  name: string
}

export interface NotifySetting {
  inboxEnabled: boolean
  inboxUsers: NotifyUserItem[]
  webhookEnabled: boolean
  webhookUrl: string
  webhookFormat: string
  repeatNotifyMinutes: number
}

export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  records: T[]
}

export interface DashboardSummary {
  total: number
  up: number
  down: number
  unknown: number
  disabled: number
  firingAlerts: number
  targets: MonTarget[]
  recentAlerts: MonAlert[]
}

// ---------------- 监控目标 ----------------
export const pageTargets = (params: Record<string, unknown>) =>
  request.get<never, PageResult<MonTarget>>('/monitor/target/page', { params })
export const getTarget = (id: Id) => request.get<never, MonTarget>(`/monitor/target/${id}`)
export const createTarget = (data: Partial<MonTarget>) => request.post('/monitor/target', data)
export const updateTarget = (data: Partial<MonTarget>) => request.put('/monitor/target', data)
export const deleteTarget = (id: Id) => request.delete(`/monitor/target/${id}`)
export const changeTargetStatus = (id: Id, enable: boolean) =>
  request.post(`/monitor/target/${id}/status?enable=${enable}`)
export const probeTarget = (id: Id) =>
  request.post<never, { status: string; latencyMs?: number; error?: string; metrics?: Record<string, unknown> }>(
    `/monitor/target/${id}/probe`
  )

// ---------------- Redis 慢命令抓取（排查工具，开启期间每轮采集读取目标 SLOWLOG） ----------------
export interface SlowCaptureStatus {
  capturing: boolean
  enabled: boolean
  startAt?: string | null
  endAt?: string | null
}

export const getSlowCapture = (id: Id) =>
  request.get<never, SlowCaptureStatus>(`/monitor/target/${id}/slow-capture`)
export const setSlowCapture = (
  id: Id,
  data: { enabled: boolean; minutes?: number; startAt?: string; endAt?: string }
) => request.post<never, SlowCaptureStatus>(`/monitor/target/${id}/slow-capture`, data)

// ---------------- 采样记录 / 总览 ----------------
export const listRecords = (targetId: Id, hours: number) =>
  request.get<never, MonRecord[]>('/monitor/record/list', { params: { targetId, hours } })
export const dashboardSummary = () => request.get<never, DashboardSummary>('/monitor/dashboard/summary')

// ---------------- 告警 ----------------
export const pageAlerts = (params: Record<string, unknown>) =>
  request.get<never, PageResult<MonAlert>>('/monitor/alert/page', { params })
export const handleAlert = (id: Id) => request.post(`/monitor/alert/${id}/handle`)

// ---------------- 通知设置（界面可配） ----------------
export const getNotifySetting = () => request.get<never, NotifySetting>('/monitor/notify/setting')
export const saveNotifySetting = (data: NotifySetting) => request.put('/monitor/notify/setting', data)
export const testNotify = () =>
  request.post<never, Record<string, string>>('/monitor/notify/test')
export const searchUsers = (keyword: string) =>
  request.get<never, Array<{ id: number; username: string; nickname?: string }>>('/monitor/notify/users', {
    params: { keyword }
  })

// ---------------- Redis 缓存监控（删键按钮按 monitor:cache:delete 权限显隐） ----------------
export interface RedisTargetItem {
  id: Id
  name: string
  host?: string
  port?: number
  status: string
}

export interface RedisInfo {
  version?: string
  os?: string
  uptimeDays?: number
  usedMemoryMb?: number
  maxmemoryMb?: number
  memUsagePct?: number
  connectedClients?: number
  opsPerSec?: number
  hitRatePct?: number
  dbsize?: number
  keyspace: Record<string, { keys: number; expires: number; avg_ttl?: number }>
}

/** 键名全集（SCAN 收集上限内；capped=true 表示被截断，total 为已收集数量） */
export interface RedisKeyNames {
  total: number
  capped: boolean
  names: string[]
}

export interface RedisKeyDetail {
  name: string
  type: string
  ttlMs: number
  encoding?: string | null
  memoryUsage?: number | null
  length?: number | null
  truncated?: boolean
  valueKind: 'json' | 'text' | 'binary' | 'map' | 'array' | 'scored' | 'stream' | 'unsupported'
  value?: unknown
}

export const listRedisTargets = () => request.get<never, RedisTargetItem[]>('/monitor/redis/targets')
export const getRedisInfo = (targetId: Id, db: number) =>
  request.get<never, RedisInfo>(`/monitor/redis/${targetId}/info`, { params: { db } })
export const listRedisKeyNames = (targetId: Id, params: { db: number; pattern?: string }) =>
  request.get<never, RedisKeyNames>(`/monitor/redis/${targetId}/key-names`, { params })
export const getRedisKeyDetail = (targetId: Id, params: { db: number; name: string }) =>
  request.get<never, RedisKeyDetail>(`/monitor/redis/${targetId}/key`, { params })
export const deleteRedisKey = (targetId: Id, params: { db: number; name: string }) =>
  request.delete<never, number>(`/monitor/redis/${targetId}/key`, { params })
