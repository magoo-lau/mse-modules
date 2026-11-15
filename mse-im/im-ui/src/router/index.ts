import { createRouter, createWebHistory } from 'vue-router'

// 嵌入主应用时以主应用路由前缀为 base（由主应用 props 下发），独立运行时使用根路径
const embedded = !!window.__POWERED_BY_WUJIE__
const base = embedded ? window.$wujie?.props?.urlPrefix || '/sub/im' : '/'

const router = createRouter({
  history: createWebHistory(base),
  routes: [
    { path: '/', redirect: '/settings' },
    { path: '/settings', component: () => import('../views/Settings.vue'), meta: { title: 'IM 设置' } },
    { path: '/bot', component: () => import('../views/Bot.vue'), meta: { title: '机器人' } },
    { path: '/workbench', component: () => import('../views/Workbench.vue'), meta: { title: '工作台配置' } },
    // 平台端页面（菜单 520，scope=1 仅平台端可见；租户直连 URL 由后端 im:provision:manage 权限拒绝）
    { path: '/provision', component: () => import('../views/Provision.vue'), meta: { title: 'IM 开通' } }
  ]
})

export default router
