import { http } from './http'
import type {
  ApprovalBatchVO,
  ApprovalRequestVO,
  HandleApprovalBatchRequest,
  HandleApprovalRequest,
} from '@/types/api'

export const approvalApi = {
  listPending: () => http.get<ApprovalRequestVO[]>('/approvals/pending'),
  listHistory: () => http.get<ApprovalRequestVO[]>('/approvals/history'),
  handle: (approvalId: number, body: HandleApprovalRequest) =>
    http.post<void>(`/approvals/${approvalId}/handle`, body),

  // 批量审批（spawn_subagent 聚合，一张卡覆盖多路子请求）
  listPendingBatches: () => http.get<ApprovalBatchVO[]>('/approvals/batches/pending'),
  listBatchHistory: () => http.get<ApprovalBatchVO[]>('/approvals/batches/history'),
  handleBatch: (batchId: number, body: HandleApprovalBatchRequest) =>
    http.post<void>(`/approvals/batches/${batchId}/handle`, body),
}
