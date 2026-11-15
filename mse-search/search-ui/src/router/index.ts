import { createRouter, createWebHistory } from 'vue-router'

// 嵌入主应用时以主应用路由前缀为 base（由主应用 props 下发），独立运行时使用根路径
const embedded = !!window.__POWERED_BY_WUJIE__
const base = embedded ? window.$wujie?.props?.urlPrefix || '/sub/search' : '/'

const router = createRouter({
  history: createWebHistory(base),
  routes: [
    { path: '/', redirect: '/source' },
    { path: '/source', component: () => import('../views/SourceManage.vue') },
    { path: '/task', component: () => import('../views/TaskManage.vue') },
    { path: '/hot', component: () => import('../views/HotKeywords.vue') },
    { path: '/playground', component: () => import('../views/SearchPlayground.vue') },
    { path: '/index', component: () => import('../views/IndexManage.vue') },
    { path: '/tenant-field', component: () => import('../views/TenantFieldConfig.vue') }
  ]
})

export default router
