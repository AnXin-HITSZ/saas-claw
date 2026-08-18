import { http } from './http'
import type { Tool } from '@/types/api'

/** 工具列表（全局共享，任意登录用户可查看当前平台有哪些工具）。 */
export const toolApi = {
  list: () => http.get<Tool[]>('/tools'),
}
