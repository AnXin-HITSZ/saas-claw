<template>
  <div class="workspace-layout">
    <!-- Top Navigation Bar -->
    <header class="topbar">
      <div class="topbar-inner">
        <router-link to="/workspace" class="logo-link">
          <svg width="28" height="28" viewBox="0 0 64 64" fill="none" class="logo-mark">
            <rect width="64" height="64" rx="16" fill="url(#topbar-grad)" />
            <path d="M18 46V18l14 10-14 10z" fill="#0a0e14" />
            <path d="M32 38l14-10-14-10v20z" fill="#0a0e14" opacity="0.7" />
            <defs>
              <linearGradient id="topbar-grad" x1="0" y1="0" x2="64" y2="64">
                <stop offset="0%" stop-color="#f0a33a" />
                <stop offset="100%" stop-color="#c78a2e" />
              </linearGradient>
            </defs>
          </svg>
          <span class="logo-text">PyClaw</span>
        </router-link>

        <nav class="topbar-nav">
          <!-- 工作台 -->
          <div class="nav-group" @mouseenter="openMenu = 'workspace'" @mouseleave="openMenu = null">
            <button class="nav-trigger" :class="{ active: isActive('/workspace/claws') || isActive('/workspace/playground') || isActive('/workspace/tools') || isActive('/workspace/pods') }">
              工作台
            </button>
            <div class="nav-dropdown" v-show="openMenu === 'workspace'">
              <router-link to="/workspace/claws" class="dropdown-item">🦀 Claw 管理</router-link>
              <router-link to="/workspace/playground" class="dropdown-item">💬 Agent 对话</router-link>
              <router-link to="/workspace/tools" class="dropdown-item">🔧 工具目录</router-link>
              <router-link to="/workspace/pods" class="dropdown-item">📦 Pod 状态</router-link>
            </div>
          </div>

          <!-- 配置 -->
          <div class="nav-group" @mouseenter="openMenu = 'config'" @mouseleave="openMenu = null">
            <button class="nav-trigger" :class="{ active: isActive('/workspace/agents') || isActive('/workspace/providers') || isActive('/workspace/secrets') || isActive('/workspace/tokens') }">
              配置
            </button>
            <div class="nav-dropdown" v-show="openMenu === 'config'">
              <router-link to="/workspace/agents" class="dropdown-item">🤖 Agent 配置</router-link>
              <router-link to="/workspace/providers" class="dropdown-item">⚡ Provider 管理</router-link>
              <router-link to="/workspace/secrets" class="dropdown-item">🔒 Secret 管理</router-link>
              <router-link to="/workspace/tokens" class="dropdown-item">🔑 API Token</router-link>
            </div>
          </div>

          <!-- 管理后台 (admin only) -->
          <div v-if="isAdmin" class="nav-group" @mouseenter="openMenu = 'admin'" @mouseleave="openMenu = null">
            <button class="nav-trigger" :class="{ active: isActive('/workspace/admin/') }">
              管理后台
            </button>
            <div class="nav-dropdown" v-show="openMenu === 'admin'">
              <router-link to="/workspace/admin/users" class="dropdown-item">👥 用户管理</router-link>
              <router-link to="/workspace/admin/channels" class="dropdown-item">📡 渠道管理</router-link>
              <router-link to="/workspace/admin/audit" class="dropdown-item">📋 审计日志</router-link>
              <router-link to="/workspace/admin/usage" class="dropdown-item">📊 用量统计</router-link>
            </div>
          </div>
        </nav>

        <div class="topbar-right">
          <span class="user-info">{{ user?.username }}</span>
          <button class="btn-logout" @click="handleLogout">退出</button>
        </div>
      </div>
    </header>

    <!-- Content Area (centered) -->
    <main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </main>
  </div>
</template>

<script setup>
import { ref, computed } from "vue";
import { useRouter, useRoute } from "vue-router";
import { useAuth } from "../composables/useAuth.js";

const router = useRouter();
const route = useRoute();
const { user, isAdmin, logout } = useAuth();
const openMenu = ref(null);

function isActive(prefix) {
  return route.path.startsWith(prefix);
}

function handleLogout() {
  logout();
  router.push("/");
}
</script>

<style scoped>
/* ── Layout ── */
.workspace-layout {
  min-height: 100vh;
  background: var(--bg-deep);
}

/* ── Topbar ── */
.topbar {
  position: sticky; top: 0; z-index: 50;
  height: var(--topbar-height);
  background: rgba(17, 22, 29, 0.85);
  backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--border);
}

.topbar-inner {
  max-width: var(--content-max-width);
  margin: 0 auto;
  padding: 0 var(--content-gutter);
  height: 100%;
  display: flex; align-items: center; gap: 32px;
}

/* Logo */
.logo-link { display: flex; align-items: center; gap: 8px; color: var(--text-primary); text-decoration: none; flex-shrink: 0; }
.logo-mark { transition: transform 0.3s var(--ease-spring); }
.logo-link:hover .logo-mark { transform: rotate(-8deg) scale(1.05); }
.logo-text {
  font-size: 16px; font-weight: 800;
  background: linear-gradient(135deg, var(--accent), var(--accent-soft));
  -webkit-background-clip: text; -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: -0.5px;
}

/* Navigation */
.topbar-nav { display: flex; align-items: center; gap: 4px; flex: 1; }

.nav-group { position: relative; }

.nav-trigger {
  padding: 6px 14px; font-size: 13px; font-weight: 600; color: var(--text-secondary);
  background: transparent; border: none; border-radius: var(--radius-sm);
  transition: all 0.2s var(--ease-out);
  letter-spacing: 0.1px;
}
.nav-trigger:hover { color: var(--text-primary); background: var(--bg-raised); }
.nav-trigger.active { color: var(--accent); background: var(--accent-glow); }

.nav-dropdown {
  position: absolute; top: calc(100% + 6px); left: 0;
  min-width: 180px; padding: 6px;
  background: var(--bg-surface); border: 1px solid var(--border);
  border-radius: var(--radius); box-shadow: var(--shadow);
  animation: dropdown-enter 0.2s var(--ease-spring);
  transform-origin: top left;
}
@keyframes dropdown-enter {
  from { opacity: 0; transform: scale(0.95) translateY(-4px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

.dropdown-item {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; border-radius: var(--radius-sm);
  font-size: 13px; font-weight: 500; color: var(--text-secondary);
  text-decoration: none;
  transition: all 0.15s var(--ease-out);
}
.dropdown-item:hover { background: var(--accent-glow); color: var(--accent); }
.dropdown-item.router-link-active { background: var(--accent-glow); color: var(--accent); }

/* Right side */
.topbar-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }

.user-info { font-size: 13px; color: var(--text-secondary); font-weight: 500; }

.btn-logout {
  padding: 5px 14px; font-size: 12px; font-weight: 500;
  color: var(--text-muted); background: transparent;
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  transition: all 0.2s var(--ease-out);
}
.btn-logout:hover { color: var(--danger); border-color: var(--danger); background: rgba(248,81,73,0.06); }

/* ── Content ── */
.main-content {
  max-width: var(--content-max-width);
  margin: 0 auto;
  padding: var(--content-gutter);
}

/* Page transitions */
.page-enter-active { transition: opacity 0.2s var(--ease-out), transform 0.2s var(--ease-out); }
.page-leave-active { transition: opacity 0.1s var(--ease-out); }
.page-enter-from { opacity: 0; transform: translateY(6px); }
.page-leave-to { opacity: 0; }
</style>
