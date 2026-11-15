import { createRouter, createWebHistory } from 'vue-router'

// 嵌入主应用时以主应用路由前缀为 base（由主应用 props 下发），独立运行时使用根路径
const embedded = !!window.__POWERED_BY_WUJIE__
const base = embedded ? window.$wujie?.props?.urlPrefix || '/sub/monitor' : '/'

const router = createRouter({
  history: createWebHistory(base),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '监控总览' } },
    { path: '/targets', component: () => import('../views/Targets.vue'), meta: { title: '监控目标' } },
    { path: '/targets/:id', component: () => import('../views/TargetDetail.vue'), meta: { title: '目标详情' } },
    { path: '/alerts', component: () => import('../views/Alerts.vue'), meta: { title: '告警中心' } },
    { path: '/redis', component: () => import('../views/RedisMonitor.vue'), meta: { title: '缓存监控' } }
  ]
})

export default router
