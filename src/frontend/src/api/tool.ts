import { http } from './http'
import type { Tool, ToolCreateRequest, ToolUpdateRequest } from '@/types/api'

/** 工具列表（全局共享，任意登录用户可查看当前平台启用工具；管理接口仅管理员）。 */
export const toolApi = {
  list: () => http.get<Tool[]>('/tools'),
  /** 管理员：全部工具（含停用） */
  listAll: () => http.get<Tool[]>('/tools/all'),
  /** 管理员：新增工具 */
  create: (body: ToolCreateRequest) => http.post<Tool>('/tools', body),
  /** 管理员：更新元数据 / 敏感度 / 启停 */
  update: (id: number, body: ToolUpdateRequest) => http.put<Tool>(`/tools/${id}`, body),
  /** 管理员：删除工具 */
  remove: (id: number) => http.delete<void>(`/tools/${id}`),
}