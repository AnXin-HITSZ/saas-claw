<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api'
import AppButton from '@/components/ui/AppButton.vue'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref<'login' | 'register'>('login')
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    if (mode.value === 'login') {
      await auth.login({ username: form.username, password: form.password })
    } else {
      await auth.register({ username: form.username, password: form.password })
    }
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '请求失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="card-glow" />

      <div class="login-brand">
        <span class="brand-logo">◢</span>
        <div class="brand-text">
          <div class="brand-name">SaasClaw</div>
          <div class="brand-sub">{{ mode === 'login' ? '登录控制台' : '注册新账号' }}</div>
        </div>
      </div>

      <div v-if="error" class="alert alert-error">{{ error }}</div>

      <div class="form-item">
        <label>用户名</label>
        <input v-model="form.username" class="input" placeholder="用户名" autocomplete="username" @keyup.enter="submit" />
      </div>
      <div class="form-item">
        <label>密码</label>
        <input
          v-model="form.password"
          type="password"
          class="input"
          placeholder="密码"
          autocomplete="current-password"
          @keyup.enter="submit"
        />
      </div>

      <AppButton class="login-submit" :loading="loading" @click="submit">
        {{ loading ? '' : mode === 'login' ? '登录' : '注册' }}
      </AppButton>

      <div class="login-switch">
        <template v-if="mode === 'login'">
          还没有账号？<a @click="mode = 'register'">去注册</a>
        </template>
        <template v-else>
          已有账号？<a @click="mode = 'login'">去登录</a>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

/* 增强背景氛围 */
.login-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(600px 320px at 50% 18%, rgba(245, 168, 61, 0.12), transparent 60%),
    radial-gradient(520px 300px at 20% 85%, rgba(139, 124, 246, 0.1), transparent 60%),
    radial-gradient(460px 280px at 85% 70%, rgba(77, 208, 225, 0.07), transparent 60%);
  pointer-events: none;
}

.login-card {
  position: relative;
  width: 400px;
  padding: 40px 38px 34px;
  border-radius: var(--radius-lg);
  background: rgba(16, 21, 31, 0.82);
  backdrop-filter: blur(18px);
  border: 1px solid var(--border);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.5), 0 0 40px rgba(139, 124, 246, 0.08);
}

/* 顶部极光细线 */
.card-glow {
  position: absolute;
  top: 0;
  left: 24px;
  right: 24px;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--accent), var(--accent-3), transparent);
  opacity: 0.7;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}
.brand-logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 13px;
  background: var(--gradient-aurora);
  box-shadow: var(--glow-accent);
  color: #0a0e14;
  font-size: 24px;
  font-weight: 700;
  flex-shrink: 0;
}
.brand-text {
  min-width: 0;
}
.brand-name {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--text-primary);
}
.brand-sub {
  font-size: 13px;
  color: var(--text-muted);
  margin-top: 2px;
}

.login-submit {
  width: 100%;
  margin-top: 6px;
}
.login-switch {
  text-align: center;
  margin-top: 18px;
  font-size: 13px;
  color: var(--text-muted);
}
.login-switch a {
  color: var(--accent);
  cursor: pointer;
  font-weight: 600;
}
.login-switch a:hover {
  text-decoration: underline;
}
</style>