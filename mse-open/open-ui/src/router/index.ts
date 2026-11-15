import { createRouter, createWebHistory } from 'vue-router'

// 嵌入主应用时以主应用路由前缀为 base（由主应用 props 下发），独立运行时使用根路径
const embedded = !!window.__POWERED_BY_WUJIE__
const base = embedded ? window.$wujie?.props?.urlPrefix || '/sub/open' : '/'

const router = createRouter({
  history: createWebHistory(base),
  routes: [
    { path: '/', redirect: '/apps' },
    { path: '/apps', component: () => import('../views/Apps.vue') },
    { path: '/apis', component: () => import('../views/Apis.vue') },
    { path: '/logs', component: () => import('../views/Logs.vue') },
    { path: '/binds', component: () => import('../views/Binds.vue') },
    { path: '/guide', component: () => import('../views/Guide.vue') }
  ]
})

export default router
