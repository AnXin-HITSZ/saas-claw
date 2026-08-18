import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api'
import { setToken, clearToken, getToken } from '@/api/http'
import type { LoginRequest, RegisterRequest, LoginVO } from '@/types/api'

const USER_KEY = 'saas_claw_user'

function loadUser(): LoginVO | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as LoginVO
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<LoginVO | null>(loadUser())
  const token = ref<string | null>(getToken())

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => user.value?.role === 1)
  const displayName = computed(() => user.value?.nickname || user.value?.username || '')

  function persist(vo: LoginVO) {
    user.value = vo
    token.value = vo.token
    setToken(vo.token)
    localStorage.setItem(USER_KEY, JSON.stringify(vo))
  }

  async function login(body: LoginRequest) {
    const vo = await authApi.login(body)
    persist(vo)
    return vo
  }

  async function register(body: RegisterRequest) {
    const vo = await authApi.register(body)
    persist(vo)
    return vo
  }

  function logout() {
    user.value = null
    token.value = null
    clearToken()
    localStorage.removeItem(USER_KEY)
  }

  return { user, token, isLoggedIn, isAdmin, displayName, login, register, logout }
})
