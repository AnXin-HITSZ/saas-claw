import { http } from './http'
import type {
  ModelConfig,
  ModelConfigCreateRequest,
  ModelConfigUpdateRequest,
} from '@/types/api'

/** 读列表任意登录用户可访问；创建/修改/删除仅管理员（后端 @RequireAdmin）。 */
export const modelConfigApi = {
  list: () => http.get<ModelConfig[]>('/model-configs'),
  create: (body: ModelConfigCreateRequest) => http.post<ModelConfig>('/model-configs', body),
  update: (id: number, body: ModelConfigUpdateRequest) =>
    http.put<ModelConfig>(`/model-configs/${id}`, body),
  remove: (id: number) => http.delete<void>(`/model-configs/${id}`),
}
