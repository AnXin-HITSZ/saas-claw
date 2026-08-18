import { http } from './http'
import type { Skill, SkillCreateRequest, SkillUpdateRequest, SkillFileVO } from '@/types/api'

export const skillApi = {
  // 用户命名空间
  list: () => http.get<Skill[]>('/skills'),
  create: (body: SkillCreateRequest) => http.post<Skill>('/skills', body),
  update: (id: number, body: SkillUpdateRequest) => http.put<Skill>(`/skills/${id}`, body),
  remove: (id: number) => http.delete<void>(`/skills/${id}`),

  // 平台命名空间（仅管理员）
  createPlatform: (body: SkillCreateRequest) => http.post<Skill>('/skills/platform', body),
  updatePlatform: (id: number, body: SkillUpdateRequest) =>
    http.put<Skill>(`/skills/platform/${id}`, body),
  removePlatform: (id: number) => http.delete<void>(`/skills/platform/${id}`),

  // 技能文件（multipart）
  listFiles: (skillId: number) => http.get<SkillFileVO[]>(`/skills/${skillId}/files`),
  uploadFile: (skillId: number, file: File, path: string) => {
    const form = new FormData()
    form.append('file', file)
    form.append('path', path)
    return http.post<SkillFileVO>(`/skills/${skillId}/files`, form)
  },
  deleteFile: (skillId: number, fileId: number) =>
    http.delete<void>(`/skills/${skillId}/files/${fileId}`),
}
