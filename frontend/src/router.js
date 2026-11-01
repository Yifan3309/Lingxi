import { createRouter, createWebHashHistory } from 'vue-router'

/**
 * 四页路由：/notes 工作台（同步加载，首屏）、/graph /concepts /review 懒加载（ECharts 只进图谱 chunk）。
 * 用 hash 模式：Spring Boot 同源托管静态产物，无需服务端 fallback 配置。
 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/notes' },
    { path: '/notes', name: 'notes', component: () => import('./views/NotesView.vue') },
    { path: '/graph', name: 'graph', component: () => import('./views/GraphView.vue') },
    { path: '/concepts', name: 'concepts', component: () => import('./views/ConceptView.vue') },
    { path: '/review', name: 'review', component: () => import('./views/ReviewView.vue') }
  ]
})
