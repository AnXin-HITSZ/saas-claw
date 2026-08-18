<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ApiError } from '@/api'

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
    <div class="login-box card">
      <div class="login-brand">SaaS Claw</div>
      <div class="login-sub">{{ mode === 'login' ? '登录控制台' : '注册新账号' }}</div>

      <div v-if="error" class="alert alert-error">{{ error }}</div>

      <div class="form-item">
        <label>用户名</label>
        <input v-model="form.username" class="input" placeholder="用户名" @keyup.enter="submit" />
      </div>
      <div class="form-item">
        <label>密码</label>
        <input
          v-model="form.password"
          type="password"
          class="input"
          placeholder="密码"
          @keyup.enter="submit"
        />
      </div>

      <button class="btn btn-primary login-submit" :disabled="loading" @click="submit">
        {{ loading ? '处理中…' : mode === 'login' ? '登录' : '注册' }}
      </button>

      <div class="login-switch">
        <span v-if="mode === 'login'">
          还没有账号？<a @click="mode = 'register'">去注册</a>
        </span>
        <span v-else> 已有账号？<a @click="mode = 'login'">去登录</a> </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eaf1ff 0%, #f5f6f8 100%);
}
.login-box {
  width: 360px;
  padding: 32px;
}
.login-brand {
  font-size: 24px;
  font-weight: 700;
  text-align: center;
}
.login-sub {
  text-align: center;
  color: var(--color-text-weak);
  margin: 6px 0 24px;
}
.login-submit {
  width: 100%;
  height: 38px;
  margin-top: 4px;
}
.login-switch {
  text-align: center;
  margin-top: 16px;
  font-size: 13px;
  color: var(--color-text-weak);
}
.login-switch a {
  color: var(--color-primary);
  cursor: pointer;
}
</style>
