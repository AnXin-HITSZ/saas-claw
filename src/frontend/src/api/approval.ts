import { http } from './http'
import type { ApprovalRequestVO, HandleApprovalRequest } from '@/types/api'

export const approvalApi = {
  listPending: () => http.get<ApprovalRequestVO[]>('/approvals/pending'),
  listHistory: () => http.get<ApprovalRequestVO[]>('/approvals/history'),
  handle: (approvalId: number, body: HandleApprovalRequest) =>
    http.post<void>(`/approvals/${approvalId}/handle`, body),
}
