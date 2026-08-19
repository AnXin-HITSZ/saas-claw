<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

interface NavItem {
  name: string
  label: string
  icon: string
  admin?: boolean
}
interface NavGroup {
  title: string
  items: NavItem[]
}

const navGroups = computed<NavGroup[]>(() => [
  {
    title: '工作区',
    items: [
      { name: 'claws', label: 'Claw 实例', icon: '▣' },
      { name: 'agents', label: 'Agent', icon: '⌂' },
      { name: 'skills', label: '技能 Skill', icon: '⚙' },
      { name: 'tools', label: '工具', icon: '▤' },
      { name: 'chat', label: '对话', icon: '◈' },
    ],
  },
  {
    title: '市场',
    items: [
      { name: 'shop-agents', label: 'Agent 市场', icon: '□' },
      { name: 'shop-skills', label: 'Skill 市场', icon: '◇' },
    ],
  },
  {
    title: '运行',
    items: [
      { name: 'approvals', label: '工具审批', icon: '◎' },
      { name: 'api-keys', label: 'API Key', icon: '⛁' },
    ],
  },
  {
    title: '管理',
    items: [
      { name: 'admin-model-configs', label: '模型配置', icon: '◈', admin: true },
      { name: 'admin-platform-skills', label: '平台技能', icon: '▥', admin: true },
    ],
  },
])

const visibleGroups = computed(() =>
  navGroups.value
    .map((g) => ({ ...g, items: g.items.filter((i) => !i.admin || auth.isAdmin) }))
    .filter((g) => g.items.length > 0),
)

const today = new Date().toLocaleDateString('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'short',
})

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="workspace-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-logo">◢</span>
        <span class="brand-name">SaasClaw</span>
      </div>

      <nav class="side-nav">
        <div v-for="g in visibleGroups" :key="g.title" class="nav-section">
          <div class="nav-section-title">{{ g.title }}</div>
          <RouterLink
            v-for="item in g.items"
            :key="item.name"
            :to="{ name: item.name }"
            class="nav-item"
            active-class="active"
          >
            <span class="nav-icon">{{ item.icon }}</span>
            <span class="nav-label">{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-footer">
        <div class="avatar">{{ auth.displayName?.charAt(0).toUpperCase() || '?' }}</div>
        <div class="user-meta">
          <div class="user-name">
            {{ auth.displayName }}
            <span v-if="auth.isAdmin" class="admin-chip">管理员</span>
          </div>
          <div class="user-date">{{ today }}</div>
        </div>
        <button class="logout-btn" title="退出登录" @click="logout">⏻</button>
      </div>
    </aside>

    <div class="workspace-main">
      <header class="mobile-topbar">
        <span class="brand-logo">◢</span>
        <span class="brand-name">SaasClaw</span>
        <button class="logout-btn" @click="logout">退出</button>
      </header>

      <main class="main-content">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.workspace-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
}

/* ---------- 侧栏 ---------- */
.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: rgba(8, 11, 17, 0.78);
  backdrop-filter: blur(14px);
  border-right: 1px solid var(--border);
  overflow-y: auto;
}

.brand {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  flex-shrink: 0;
}
.brand-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--gradient-aurora);
  box-shadow: var(--glow-accent);
  color: #0a0e14;
  font-weight: 700;
  font-size: 15px;
}
.brand-name {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.side-nav {
  flex: 1;
  padding: 8px 12px;
}
.nav-section {
  margin-bottom: 14px;
}
.nav-section-title {
  padding: 6px 12px 8px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  color: var(--text-muted);
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 36px;
  padding: 0 12px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  transition: background 0.2s var(--ease-out), color 0.2s var(--ease-out);
  position: relative;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-primary);
}
.nav-item.active {
  color: var(--accent);
  background: linear-gradient(90deg, var(--accent-glow), transparent 80%);
}
.nav-item.active::before {
  content: '';
  position: absolute;
  left: -12px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--gradient-aurora);
  transform: scaleY(1);
  transform-origin: center;
  transition: transform 0.25s var(--ease-spring);
}
.nav-icon {
  width: 18px;
  text-align: center;
  font-size: 14px;
  opacity: 0.9;
}

/* ---------- 侧栏底部用户区 ---------- */
.sidebar-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  border-top: 1px solid var(--border);
  flex-shrink: 0;
}
.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--gradient-aurora);
  color: #0a0e14;
  font-weight: 700;
  font-size: 15px;
  flex-shrink: 0;
}
.user-meta {
  flex: 1;
  min-width: 0;
}
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
  gap: 6px;
}
.admin-chip {
  font-size: 10px;
  font-weight: 600;
  color: var(--warning);
  background: rgba(224, 168, 50, 0.14);
  border-radius: 999px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.user-date {
  font-size: 11px;
  color: var(--text-muted);
}
.logout-btn {
  background: none;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--text-muted);
  font-size: 15px;
  cursor: pointer;
  padding: 6px 8px;
  transition: color 0.2s var(--ease-out), border-color 0.2s var(--ease-out);
  flex-shrink: 0;
}
.logout-btn:hover {
  color: var(--danger);
  border-color: rgba(255, 92, 92, 0.35);
}

/* ---------- 主区 ---------- */
.workspace-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.mobile-topbar {
  display: none;
}
.main-content {
  max-width: min(1180px, calc(100vw - 232px - 84px));
  width: 100%;
  margin: 0 auto;
  padding: 34px 42px 72px;
}

/* ---------- 响应式 ---------- */
@media (max-width: 860px) {
  .workspace-shell {
    display: block;
  }
  .sidebar {
    display: none;
  }
  .mobile-topbar {
    position: sticky;
    top: 0;
    z-index: 100;
    height: 56px;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 0 16px;
    background: rgba(8, 11, 17, 0.9);
    backdrop-filter: blur(14px);
    border-bottom: 1px solid var(--border);
  }
  .mobile-topbar .brand-name {
    flex: 1;
  }
  .main-content {
    padding: 20px 16px 48px;
    max-width: 100%;
  }
}

/* 页面过渡 */
.page-enter-active {
  transition: opacity 0.2s var(--ease-out), transform 0.2s var(--ease-out);
}
.page-leave-active {
  transition: opacity 0.12s ease, transform 0.12s ease;
}
.page-enter-from {
  opacity: 0;
  transform: translateY(6px);
}
.page-leave-to {
  opacity: 0;
  transform: translateY(-3px);
}
</style>