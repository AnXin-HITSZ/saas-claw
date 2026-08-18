import { reactive } from 'vue'

export interface Toast {
  id: number
  type: 'success' | 'error' | 'info'
  message: string
}

const state = reactive<{ items: Toast[] }>({ items: [] })
let seq = 1

function push(type: Toast['type'], message: string, duration = 2600) {
  const id = seq++
  state.items.push({ id, type, message })
  window.setTimeout(() => {
    const i = state.items.findIndex((t) => t.id === id)
    if (i !== -1) state.items.splice(i, 1)
  }, duration)
}

export function useToast() {
  return {
    toasts: state.items,
    success: (m: string) => push('success', m),
    error: (m: string) => push('error', m),
    info: (m: string) => push('info', m),
  }
}
