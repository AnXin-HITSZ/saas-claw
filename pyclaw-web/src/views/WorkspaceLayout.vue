<template>
  <div class="workspace-layout">
    <aside class="sidebar">
      <div class="sidebar-header">
        <router-link to="/workspace" class="logo-link">
          <svg width="28" height="28" viewBox="0 0 64 64" fill="none">
            <rect width="64" height="64" rx="16" fill="#58a6ff" />
            <path d="M20 44V20l12 8-12 8z" fill="#0d1117" />
            <path d="M32 36l12-8-12-8v16z" fill="#0d1117" opacity="0.7" />
          </svg>
          <span class="logo-text">PyClaw</span>
        </router-link>
      </div>

      <nav class="sidebar-nav">
        <div class="nav-section">
          <span class="nav-section-title">工作台</span>
          <router-link to="/workspace/claws" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F980;</span> Claw 管理
          </router-link>
          <router-link to="/workspace/playground" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F4AC;</span> Agent 对话
          </router-link>
          <router-link to="/workspace/tools" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F527;</span> 工具目录
          </router-link>
          <router-link to="/workspace/pods" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F4E6;</span> Pod 状态
          </router-link>
        </div>

        <div class="nav-section">
          <span class="nav-section-title">配置</span>
          <router-link to="/workspace/agents" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F916;</span> Agent 配置
          </router-link>
          <router-link to="/workspace/providers" class="nav-item" active-class="active">
            <span class="nav-icon">&#x26A1;</span> Provider 管理
          </router-link>
          <router-link to="/workspace/secrets" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F512;</span> Secret 管理
          </router-link>
          <router-link to="/workspace/tokens" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F511;</span> API Token
          </router-link>
        </div>

        <div v-if="isAdmin" class="nav-section">
          <span class="nav-section-title">管理后台</span>
          <router-link to="/workspace/admin/users" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F465;</span> 用户管理
          </router-link>
          <router-link to="/workspace/admin/channels" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F4E1;</span> 渠道管理
          </router-link>
          <router-link to="/workspace/admin/audit" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F4CB;</span> 审计日志
          </router-link>
          <router-link to="/workspace/admin/usage" class="nav-item" active-class="active">
            <span class="nav-icon">&#x1F4CA;</span> 用量统计
          </router-link>
        </div>
      </nav>

      <div class="sidebar-footer">
        <span class="user-info">{{ user?.username }}</span>
        <button class="btn-logout" @click="handleLogout">退出</button>
      </div>
    </aside>

    <main class="main-content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRouter } from "vue-router";
import { useAuth } from "../composables/useAuth.js";

const router = useRouter();
const { user, isAdmin, logout } = useAuth();

function handleLogout() {
  logout();
  router.push("/");
}
</script>

<style scoped>
.workspace-layout {
  display: flex;
  min-height: 100vh;
}

.sidebar {
  width: var(--sidebar-width);
  background: var(--bg-secondary);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: 10;
}

.sidebar-header {
  padding: 20px;
  border-bottom: 1px solid var(--border-color);
}

.logo-link {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
  text-decoration: none;
}

.logo-text {
  font-size: 18px;
  font-weight: 700;
}

.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 12px 0;
}

.nav-section {
  margin-bottom: 8px;
}

.nav-section-title {
  display: block;
  padding: 8px 20px 4px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--text-muted);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 20px;
  font-size: 14px;
  color: var(--text-secondary);
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover {
  background: var(--bg-tertiary);
  color: var(--text-primary);
}

.nav-item.active {
  background: rgba(88, 166, 255, 0.1);
  color: var(--accent);
}

.nav-icon {
  width: 20px;
  text-align: center;
  font-size: 16px;
}

.sidebar-footer {
  padding: 16px 20px;
  border-top: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.user-info {
  font-size: 13px;
  color: var(--text-secondary);
}

.btn-logout {
  padding: 4px 12px;
  font-size: 12px;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border-color);
  border-radius: 4px;
  transition: color 0.15s, border-color 0.15s;
}

.btn-logout:hover {
  color: var(--danger);
  border-color: var(--danger);
}

.main-content {
  flex: 1;
  margin-left: var(--sidebar-width);
  padding: 32px;
  min-height: 100vh;
}
</style>
