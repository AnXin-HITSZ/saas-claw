<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import ToastHost from '@/components/ToastHost.vue'

const auth = useAuthStore()
const router = useRouter()

interface NavItem {
  name: string
  label: string
  admin?: boolean
}
interface NavGroup {
  title: string
  items: NavItem[]
}

const groups = computed<NavGroup[]>(() => [
  {
    title: '工作区',
    items: [
      { name: 'claws', label: 'Claw 实例' },
      { name: 'agents', label: 'Agent' },
      { name: 'skills', label: '技能 Skill' },
      { name: 'tools', label: '工具' },
      { name: 'chat', label: '对话' },
    ],
  },
  {
    title: '市场',
    items: [
      { name: 'shop-agents', label: 'Agent 市场' },
      { name: 'shop-skills', label: 'Skill 市场' },
    ],
  },
  {
    title: '运行',
    items: [
      { name: 'approvals', label: '工具审批' },
      { name: 'api-keys', label: 'API Key' },
    ],
  },
  {
    title: '管理',
    items: [
      { name: 'admin-model-configs', label: '模型配置', admin: true },
      { name: 'admin-platform-skills', label: '平台技能', admin: true },
    ],
  },
])

// 管理组仅管理员可见；组内全为 admin 项且非管理员时整组隐藏
const visibleGroups = computed(() =>
  groups.value
    .map((g) => ({ ...g, items: g.items.filter((i) => !i.admin || auth.isAdmin) }))
    .filter((g) => g.items.length > 0),
)

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">SaaS Claw</div>
      <nav class="nav">
        <div v-for="g in visibleGroups" :key="g.title" class="nav-group">
          <div class="nav-group-title">{{ g.title }}</div>
          <RouterLink
            v-for="item in g.items"
            :key="item.name"
            :to="{ name: item.name }"
            class="nav-item"
            active-class="active"
          >
            {{ item.label }}
          </RouterLink>
        </div>
      </nav>
    </aside>

    <div class="main">
      <header class="header">
        <div class="spacer" />
        <div class="user">
          <span class="text-weak">{{ auth.displayName }}</span>
          <span v-if="auth.isAdmin" class="tag tag-warning">管理员</span>
          <button class="btn btn-sm" @click="logout">退出</button>
        </div>
      </header>
      <main class="content">
        <RouterView />
      </main>
    </div>

    <ToastHost />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
}
.sidebar {
  width: var(--sidebar-w);
  background: #1f2329;
  color: #c9cdd4;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  overflow-y: auto;
}
.brand {
  height: var(--header-h);
  display: flex;
  align-items: center;
  padding: 0 20px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  letter-spacing: 0.5px;
}
.nav {
  padding: 8px 0;
}
.nav-group {
  margin-bottom: 8px;
}
.nav-group-title {
  padding: 8px 20px 4px;
  font-size: 12px;
  color: #767c85;
}
.nav-item {
  display: block;
  padding: 8px 20px;
  color: #c9cdd4;
  font-size: 14px;
  cursor: pointer;
  border-left: 3px solid transparent;
}
.nav-item:hover {
  background: #2b3038;
  color: #fff;
}
.nav-item.active {
  background: #2b3038;
  color: #fff;
  border-left-color: var(--color-primary);
}
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.header {
  height: var(--header-h);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  padding: 0 24px;
  flex-shrink: 0;
}
.user {
  display: flex;
  align-items: center;
  gap: 12px;
}
.content {
  flex: 1;
  overflow-y: auto;
}
</style>
