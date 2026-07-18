import { reactive, readonly } from "vue";

let seq = 0;
const toasts = reactive([]);

function push(type, message, duration = 3500) {
  const id = ++seq;
  toasts.push({ id, type, message });
  setTimeout(() => {
    const idx = toasts.findIndex(t => t.id === id);
    if (idx !== -1) toasts.splice(idx, 1);
  }, duration);
}

export function useToast() {
  return {
    toasts: readonly(toasts),
    toast: {
      success: msg => push("success", msg),
      error: msg => push("error", msg),
      info: msg => push("info", msg),
    },
  };
}

// 内部使用：AppToast 渲染需要可写数组
export function useToastStore() {
  return { toasts };
}
