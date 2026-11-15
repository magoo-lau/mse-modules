import { createRouter, createWebHistory } from 'vue-router'

// 嵌入主应用时以主应用路由前缀为 base（由主应用 props 下发），独立运行时使用根路径
const embedded = !!window.__POWERED_BY_WUJIE__
const base = embedded ? window.$wujie?.props?.urlPrefix || '/sub/ai' : '/'

const router = createRouter({
  history: createWebHistory(base),
  routes: [
    { path: '/', redirect: '/chat' },
    // 租户端
    { path: '/chat', component: () => import('../views/Chat.vue') },
    { path: '/agent', component: () => import('../views/AgentManage.vue') },
    { path: '/agent/flow/:id', component: () => import('../views/flow/FlowEditor.vue') },
    { path: '/kb', component: () => import('../views/KbManage.vue') },
    { path: '/usage', component: () => import('../views/UsageStats.vue') },
    { path: '/tool', component: () => import('../views/ToolList.vue') },
    // 页面菜单组件载体（466 模型管理 / 467 开放能力已转租户端菜单，路由沿用 /platform 前缀）
    { path: '/platform/model', component: () => import('../views/PlatformModel.vue') },
    { path: '/platform/agent/flow/:id', redirect: '/agent/flow/:id' },
    { path: '/platform/tool', redirect: '/tool' },
    { path: '/platform/open', component: () => import('../views/PlatformOpen.vue') },
    { path: '/platform/usage', component: () => import('../views/PlatformUsage.vue') }
  ]
})

export default router
