import { http } from './http'
import type {
  Agent,
  AgentCreateRequest,
  AgentUpdateRequest,
  BindSkillRequest,
  Skill,
  AgentFileVO,
} from '@/types/api'

export const agentApi = {
  list: (clawId?: number) =>
    http.get<Agent[]>('/agents', { params: clawId != null ? { clawId } : undefined }),
  create: (body: AgentCreateRequest) => http.post<Agent>('/agents', body),
  update: (id: number, body: AgentUpdateRequest) => http.put<Agent>(`/agents/${id}`, body),
  remove: (id: number) => http.delete<void>(`/agents/${id}`),

  // 绑定技能
  listSkills: (agentId: number) => http.get<Skill[]>(`/agents/${agentId}/skills`),
  bindSkill: (agentId: number, body: BindSkillRequest) =>
    http.post<void>(`/agents/${agentId}/skills`, body),
  unbindSkill: (agentId: number, skillId: number) =>
    http.delete<void>(`/agents/${agentId}/skills/${skillId}`),

  // 人格/文件（AGENTS.md / IDENTITY.md / SOUL.md 等，multipart）
  listFiles: (agentId: number) => http.get<AgentFileVO[]>(`/agents/${agentId}/files`),
  uploadFile: (agentId: number, file: File, path: string) => {
    const form = new FormData()
    form.append('file', file)
    form.append('path', path)
    return http.post<AgentFileVO>(`/agents/${agentId}/files`, form)
  },
  deleteFile: (agentId: number, fileId: number) =>
    http.delete<void>(`/agents/${agentId}/files/${fileId}`),
}
