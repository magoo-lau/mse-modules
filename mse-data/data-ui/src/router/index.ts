import { createRouter, createWebHistory } from 'vue-router'

// 嵌入主应用时以主应用路由前缀为 base（由主应用 props 下发），独立运行时使用根路径
const embedded = !!window.__POWERED_BY_WUJIE__
const base = embedded ? window.$wujie?.props?.urlPrefix || '/sub/data' : '/'

const router = createRouter({
  history: createWebHistory(base),
  routes: [
    { path: '/', redirect: '/overview' },
    { path: '/overview', component: () => import('../views/Overview.vue') },
    { path: '/datasource', component: () => import('../views/Datasource.vue') },
    { path: '/sync', component: () => import('../views/SyncJob.vue') },
    { path: '/datamap', component: () => import('../views/DataMap.vue') },
    { path: '/service', component: () => import('../views/DataService.vue') },
    { path: '/quality', component: () => import('../views/Quality.vue') },
    { path: '/allowlist', component: () => import('../views/QueryAllowlist.vue') }
  ]
})

export default router
