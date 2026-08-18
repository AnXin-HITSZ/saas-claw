import { http } from './http'
import type {
  ShopAgentVO,
  InstallAgentRequest,
  AgentInstallVO,
  MyAgentInstallationVO,
  ShopSkillVO,
  InstallSkillsRequest,
  InstallBatchResultVO,
  SkillInstallation,
  MySkillInstallationVO,
} from '@/types/api'

export const shopApi = {
  // ---- Agent 市场 ----
  listAgents: () => http.get<ShopAgentVO[]>('/shop/agents'),
  publishAgent: (agentId: number) => http.post<void>(`/shop/agents/${agentId}/publish`),
  unpublishAgent: (agentId: number) => http.delete<void>(`/shop/agents/${agentId}/publish`),
  installAgent: (agentId: number, body: InstallAgentRequest) =>
    http.post<AgentInstallVO>(`/shop/agents/${agentId}/install`, body),
  myAgentInstallations: () =>
    http.get<MyAgentInstallationVO[]>('/shop/agents/my-agent-installations'),
  uninstallAgent: (installationId: number) =>
    http.delete<void>(`/shop/agents/my-agent-installations/${installationId}`),

  // ---- Skill 市场 ----
  listSkills: () => http.get<ShopSkillVO[]>('/shop/skills'),
  publishSkill: (skillId: number) => http.post<void>(`/shop/skills/${skillId}/publish`),
  unpublishSkill: (skillId: number) => http.delete<void>(`/shop/skills/${skillId}/publish`),
  installSkill: (skillId: number) =>
    http.post<SkillInstallation>(`/shop/skills/${skillId}/install`),
  installSkillsBatch: (body: InstallSkillsRequest) =>
    http.post<InstallBatchResultVO>('/shop/skills/install-batch', body),
  mySkillInstallations: () => http.get<MySkillInstallationVO[]>('/shop/my-installations'),
  uninstallSkill: (id: number, force = false) =>
    http.delete<void>(`/shop/my-installations/${id}`, { params: { force } }),
}
