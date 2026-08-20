package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.ApprovalResultVO;

import java.util.Map;

/** 审批结果发往 Claw Pod 的回调契约：runtime 侧对应 POST /approvals/callback */
public interface RuntimeCallbackService {

    /** 单条敏感工具审批处理完成后，异步通知对应 Claw Pod 恢复挂起的图执行（HTTP 回调，fire-and-forget） */
    void notifyApproval(Long clawId, ApprovalResultVO result);

    /** 批量审批处理完成后，异步通知对应 Claw Pod 恢复挂起的主图。
     *  result 为 runtime 契约的 result 载荷：{decision, reason, decisions?}（与 spawn 容器 _apply_decisions 对齐）。 */
    void notifyBatchApproval(Long clawId, String requestId, Map<String, Object> result);
}