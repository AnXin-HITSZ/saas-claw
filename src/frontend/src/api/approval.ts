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
  /** 按 requestId 直达处理（对话页审批弹窗），等价 handle，省去前端反查 DB id */
  handleByRequest: (requestId: string, body: HandleApprovalRequest) =>
    http.post<void>(`/approvals/by-request/${requestId}/handle`, body),

  // 批量审批（spawn_subagent 聚合，一张卡覆盖多路子请求）
  listPendingBatches: () => http.get<ApprovalBatchVO[]>('/approvals/batches/pending'),
  listBatchHistory: () => http.get<ApprovalBatchVO[]>('/approvals/batches/history'),
  handleBatch: (batchId: number, body: HandleApprovalBatchRequest) =>
    http.post<void>(`/approvals/batches/${batchId}/handle`, body),
  /** 按 requestId 直达处理批量审批（对话页审批弹窗），等价 handleBatch */
  handleBatchByRequest: (requestId: string, body: HandleApprovalBatchRequest) =>
    http.post<void>(`/approvals/batches/by-request/${requestId}/handle`, body),
}
