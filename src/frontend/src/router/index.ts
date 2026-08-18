import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * meta:
 * - public: 免登录（登录/注册）
 * - admin: 仅管理员（role=1）可见/可进
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    redirect: '/claws',
    children: [
      { path: 'claws', name: 'claws', component: () => import('@/views/ClawListView.vue') },
      { path: 'agents', name: 'agents', component: () => import('@/views/AgentListView.vue') },
      { path: 'skills', name: 'skills', component: () => import('@/views/SkillListView.vue') },
      { path: 'tools', name: 'tools', component: () => import('@/views/ToolListView.vue') },
      {
        path: 'shop/agents',
        name: 'shop-agents',
        component: () => import('@/views/ShopAgentView.vue'),
      },
      {
        path: 'shop/skills',
        name: 'shop-skills',
        component: () => import('@/views/ShopSkillView.vue'),
      },
      { path: 'approvals', name: 'approvals', component: () => import('@/views/ApprovalView.vue') },
      { path: 'chat', name: 'chat', component: () => import('@/views/ChatView.vue') },
      { path: 'api-keys', name: 'api-keys', component: () => import('@/views/ApiKeyView.vue') },
      // 管理员专区
      {
        path: 'admin/model-configs',
        name: 'admin-model-configs',
        component: () => import('@/views/admin/ModelConfigView.vue'),
        meta: { admin: true },
      },
      {
        path: 'admin/platform-skills',
        name: 'admin-platform-skills',
        component: () => import('@/views/admin/PlatformSkillView.vue'),
        meta: { admin: true },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.admin && !auth.isAdmin) {
    return { name: 'claws' }
  }
  return true
})

export default router
