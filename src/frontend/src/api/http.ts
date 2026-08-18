import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios'
import type { Result } from '@/types/api'

/**
 * axios 实例：
 * - baseURL='/api'（与网关 context-path 对齐；开发期 vite proxy 转发到网关）
 * - 请求注入 Bearer token
 * - 响应解包 Result 信封：code=200 取 data，否则抛业务错误
 * - 401 清理登录态并跳转登录
 */
const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

const TOKEN_KEY = 'saas_claw_token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 401 时的登出回调，由 auth store 注册，避免 http 层直接依赖 router/store。 */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void): void {
  onUnauthorized = fn
}

instance.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

/** 业务错误：携带 code/message，页面可据此提示。 */
export class ApiError extends Error {
  code: number
  constructor(code: number, message: string) {
    super(message)
    this.code = code
    this.name = 'ApiError'
  }
}

instance.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>
    // 非信封响应（极少数）直接透传
    if (body == null || typeof body !== 'object' || !('code' in body)) {
      return response
    }
    if (body.code === 200) {
      response.data = body.data
      return response
    }
    throw new ApiError(body.code, body.message || '请求失败')
  },
  (error) => {
    const status = error?.response?.status
    const body = error?.response?.data as Result<unknown> | undefined
    if (status === 401) {
      clearToken()
      onUnauthorized?.()
    }
    const code = body?.code ?? status ?? 0
    const message = body?.message || error?.message || '网络错误'
    return Promise.reject(new ApiError(code, message))
  },
)

/** 解包后的 GET/POST/PUT/DELETE，返回 data 本体（已剥离信封）。 */
export const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config).then((r) => r.data as T)
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config).then((r) => r.data as T)
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config).then((r) => r.data as T)
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config).then((r) => r.data as T)
  },
}

export default instance
