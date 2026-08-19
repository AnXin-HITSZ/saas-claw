<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppButton from '@/components/ui/AppButton.vue'

const auth = useAuthStore()
const router = useRouter()

function enter() {
  router.push(auth.isLoggedIn ? { name: 'claws' } : { name: 'login' })
}

const features = [
  { icon: '⌂', title: 'Agent 编排', desc: '多 Agent 挂载技能与人格文件，alias 即 model，对话随时调用。' },
  { icon: '▣', title: 'Claw 运行时', desc: '每个 Claw 独立部署、独立命名空间，测试与生产天然隔离。' },
  { icon: '□', title: '开放市场', desc: 'Agent 与 Skill 一键发布共享，别人发布的能力即装即用。' },
  { icon: '◎', title: '合规可控', desc: '敏感工具人工审批，API Key 隔离鉴权，全程可追踪。' },
]
</script>

<template>
  <div class="welcome">
    <header class="topbar">
      <div class="brand">
        <span class="brand-logo">◢</span>
        <span class="brand-name">SaasClaw</span>
      </div>
      <AppButton variant="ghost" size="sm" @click="router.push({ name: 'login' })">登录 / 注册</AppButton>
    </header>

    <main class="hero">
      <div class="hero-logo">◢</div>
      <h1 class="hero-title">你的 AI Agent<span class="grad"> 工作台</span></h1>
      <p class="hero-sub">
        以 Claw 为运行时、Agent 为智能体、Skill 为能力包，
        <br />一套部署即用的 Agent 调度与控制平台。
      </p>

      <div class="hero-actions">
        <AppButton @click="enter">进入控制台</AppButton>
      </div>

      <div class="feature-grid">
        <div v-for="f in features" :key="f.title" class="feature-card">
          <span class="feature-icon">{{ f.icon }}</span>
          <div class="feature-title">{{ f.title }}</div>
          <div class="feature-desc">{{ f.desc }}</div>
        </div>
      </div>
    </main>

    <footer class="footer">SaasClaw · Gateway / Backend / Runtime / Frontend 一体化平台</footer>
  </div>
</template>

<style scoped>
.welcome {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 36px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}
.brand-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: var(--gradient-aurora);
  box-shadow: var(--glow-accent);
  color: #0a0e14;
  font-weight: 700;
  font-size: 16px;
}
.brand-name {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 700;
}

.hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
  text-align: center;
}
.hero-logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 84px;
  height: 84px;
  border-radius: 24px;
  background: var(--gradient-aurora);
  box-shadow: 0 0 60px rgba(245, 168, 61, 0.35);
  color: #0a0e14;
  font-size: 42px;
  font-weight: 700;
  margin-bottom: 28px;
  animation: hero-float 5s var(--ease-in-out) infinite;
}
@keyframes hero-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-8px);
  }
}

.hero-title {
  font-family: var(--font-display);
  font-size: 44px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin: 0 0 14px;
  color: var(--text-primary);
}
.grad {
  background: var(--gradient-aurora);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.hero-sub {
  font-size: 16px;
  color: var(--text-secondary);
  line-height: 1.7;
  margin: 0 0 32px;
}

.hero-actions {
  margin-bottom: 56px;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  max-width: 900px;
  width: 100%;
}
.feature-card {
  padding: 22px 20px;
  border-radius: var(--radius-lg);
  background: rgba(16, 21, 31, 0.6);
  border: 1px solid var(--border);
  text-align: left;
  transition: transform 0.25s var(--ease-out), border-color 0.25s var(--ease-out),
    box-shadow 0.25s var(--ease-out);
}
.feature-card:hover {
  transform: translateY(-4px);
  border-color: var(--border-light);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);
}
.feature-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--accent-glow);
  color: var(--accent);
  font-size: 16px;
  margin-bottom: 12px;
}
.feature-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 6px;
}
.feature-desc {
  font-size: 12.5px;
  color: var(--text-muted);
  line-height: 1.6;
}

.footer {
  padding: 20px;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
  border-top: 1px solid var(--border);
}

@media (max-width: 860px) {
  .feature-grid {
    grid-template-columns: 1fr 1fr;
  }
  .hero-title {
    font-size: 32px;
  }
}
</style>