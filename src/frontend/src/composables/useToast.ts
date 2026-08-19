import { reactive } from 'vue'

export interface ToastItem {
  id: number
  type: 'success' | 'error' | 'info'
  message: string
}

export interface ToastApi {
  toasts: ToastItem[]
  success: (message: string, duration?: number) => void
  error: (message: string, duration?: number) => void
  info: (message: string, duration?: number) => void
  dismiss: (id: number) => void
}

const state = reactive<{ items: ToastItem[] }>({ items: [] })
let seq = 1
const timers = new Map<number, number>()

function push(type: ToastItem['type'], message: string, duration = 3500) {
  const id = seq++
  state.items.push({ id, type, message })
  const timer = window.setTimeout(() => dismiss(id), duration)
  timers.set(id, timer)
}

function dismiss(id: number) {
  const i = state.items.findIndex((t) => t.id === id)
  if (i !== -1) state.items.splice(i, 1)
  const t = timers.get(id)
  if (t) {
    clearTimeout(t)
    timers.delete(id)
  }
}

/** 模块级单例：页面任意处 useToast() 均操作同一队列，由 AppToast 渲染 */
export function useToast(): ToastApi {
  return {
    toasts: state.items,
    success: (m: string, d?: number) => push('success', m, d),
    error: (m: string, d?: number) => push('error', m, d),
    info: (m: string, d?: number) => push('info', m, d),
    dismiss,
  }
}