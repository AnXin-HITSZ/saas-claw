<template>
  <div class="workspace-shell">
    <aside class="sidebar">
      <router-link to="/workspace/claws" class="brand">
        <span class="brand-mark">▶</span>
        <span>PyClaw</span>
      </router-link>

      <nav class="side-nav" aria-label="Workspace navigation">
        <p class="nav-section">工作台</p>
        <router-link to="/workspace/claws" class="nav-item">
          <span class="nav-icon">▣</span>
          <span>Claw 管理</span>
        </router-link>

        <router-link to="/workspace/tools" class="nav-item">
          <span class="nav-icon">⌁</span>
          <span>工具目录</span>
        </router-link>
        <router-link to="/workspace/pods" class="nav-item">
          <span class="nav-icon">⌂</span>
          <span>Pod 状态</span>
        </router-link>

        <p class="nav-section">配置</p>
        <router-link to="/workspace/agents" class="nav-item">
          <span class="nav-icon">⚙</span>
          <span>Agent 配置</span>
        </router-link>
        <router-link to="/workspace/providers" class="nav-item">
          <span class="nav-icon">▤</span>
          <span>Provider 管理</span>
        </router-link>
        <router-link to="/workspace/secrets" class="nav-item">
          <span class="nav-icon">□</span>
          <span>Secret 管理</span>
        </router-link>
        <router-link to="/workspace/tokens" class="nav-item">
          <span class="nav-icon">◇</span>
          <span>API Token</span>
        </router-link>

        <template v-if="isAdmin">
          <p class="nav-section">管理后台</p>
          <router-link to="/workspace/admin/users" class="nav-item">
            <span class="nav-icon">◎</span>
            <span>用户管理</span>
          </router-link>
          <router-link to="/workspace/admin/channels" class="nav-item">
            <span class="nav-icon">◈</span>
            <span>渠道管理</span>
          </router-link>
          <router-link to="/workspace/admin/audit" class="nav-item">
            <span class="nav-icon">▥</span>
            <span>审计日志</span>
          </router-link>
          <router-link to="/workspace/admin/usage" class="nav-item">
            <span class="nav-icon">◧</span>
            <span>用量统计</span>
          </router-link>
        </template>
      </nav>

      <div class="sidebar-footer">
        <div class="workspace-chip">
          <span class="chip-title">工作区</span>
          <span class="chip-line"><i class="dot claw"></i>Claw</span>
          <span class="chip-line"><i class="dot agent"></i>Agent</span>
          <span class="chip-line"><i class="dot chat"></i>对话</span>
        </div>
        <div class="account-row">
          <span class="avatar">{{ userInitial }}</span>
          <div class="account-meta">
            <strong>{{ user?.username || "用户" }}</strong>
            <span>{{ currentDate }}</span>
          </div>
          <button class="logout-icon" type="button" @click="handleLogout" title="退出登录">↗</button>
        </div>
      </div>
    </aside>

    <div class="workspace-main">
      <header class="mobile-topbar">
        <router-link to="/workspace/claws" class="brand compact">
          <span class="brand-mark">▶</span>
          <span>PyClaw</span>
        </router-link>
        <button class="btn-logout" type="button" @click="handleLogout">退出</button>
      </header>

      <main class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { useRouter } from "vue-router";
import { useAuth } from "../composables/useAuth.js";

const router = useRouter();
const { user, isAdmin, logout } = useAuth();

const userInitial = computed(() => (user.value?.username || "U").slice(0, 1).toUpperCase());
const currentDate = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
}).format(new Date());

function handleLogout() {
  logout();
  router.push("/");
}
</script>

<style scoped>
.workspace-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  background:
    radial-gradient(circle at 70% 12%, rgba(240, 163, 58, 0.08), transparent 28%),
    var(--bg-deep);
}

.sidebar {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: rgba(9, 12, 17, 0.9);
}

.brand {
  height: 56px;
  padding: 0 18px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-primary);
  text-decoration: none;
  font-weight: 800;
  border-bottom: 1px solid var(--border);
}

.brand.compact {
  height: auto;
  padding: 0;
  border: 0;
}

.brand-mark {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 8px;
  color: #0a0e14;
  background: var(--accent);
  font-size: 12px;
  box-shadow: var(--shadow-glow);
}

.side-nav {
  padding: 18px 10px;
  flex: 1;
  overflow-y: auto;
}

.nav-section {
  margin: 16px 10px 8px;
  font-size: 11px;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.nav-section:first-child { margin-top: 0; }

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 8px 10px;
  border-radius: 8px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: background 0.16s var(--ease-out), color 0.16s var(--ease-out);
}

.nav-item:hover,
.nav-item.router-link-active {
  color: var(--accent);
  background: var(--accent-glow);
}

.nav-icon {
  width: 16px;
  color: currentColor;
  opacity: 0.85;
  text-align: center;
}

.sidebar-footer {
  padding: 12px;
  border-top: 1px solid var(--border);
}

.workspace-chip {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.02);
  color: var(--text-muted);
  font-size: 11px;
}

.chip-title {
  display: block;
  margin-bottom: 6px;
  color: var(--text-secondary);
  font-weight: 700;
}

.chip-line {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-right: 8px;
}

.dot {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  display: inline-block;
}

.dot.claw { background: var(--accent); }
.dot.agent { background: #7287ff; }
.dot.chat { background: var(--success); }

.account-row {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--accent-soft);
  color: #0a0e14;
  font-size: 12px;
  font-weight: 800;
}

.account-meta {
  min-width: 0;
  flex: 1;
  display: grid;
  line-height: 1.3;
}

.account-meta strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-meta span {
  color: var(--text-muted);
  font-size: 11px;
}

.logout-icon {
  width: 28px;
  height: 28px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: var(--text-muted);
  background: transparent;
}

.logout-icon:hover {
  color: var(--danger);
  border-color: rgba(248, 81, 73, 0.45);
  background: rgba(248, 81, 73, 0.06);
}

.workspace-main { min-width: 0; }

.mobile-topbar { display: none; }

.main-content {
  max-width: min(1180px, calc(100vw - 220px));
  margin: 0 auto;
  padding: 34px 42px 72px;
}

.btn-logout {
  padding: 5px 14px;
  font-size: 12px;
  color: var(--text-muted);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
}

.btn-logout:hover {
  color: var(--danger);
  border-color: var(--danger);
  background: rgba(248,81,73,0.06);
}

.page-enter-active { transition: opacity 0.2s var(--ease-out), transform 0.2s var(--ease-out); }
.page-leave-active { transition: opacity 0.1s var(--ease-out); }
.page-enter-from { opacity: 0; transform: translateY(6px); }
.page-leave-to { opacity: 0; }

@media (max-width: 860px) {
  .workspace-shell { display: block; }
  .sidebar { display: none; }
  .mobile-topbar {
    position: sticky;
    top: 0;
    z-index: 50;
    height: 56px;
    padding: 0 18px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 1px solid var(--border);
    background: rgba(9, 12, 17, 0.92);
    backdrop-filter: blur(10px);
  }
  .main-content { padding: 24px 18px 48px; }
}
</style>
