package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.ApprovalResultVO;

/** 审批结果发往 Claw Pod 的回调契约：runtime 侧对应 POST /approvals/callback */
public interface RuntimeCallbackService {

    /** 审批处理完成后，异步通知对应 Claw Pod 恢复挂起的图执行（HTTP 回调，fire-and-forget） */
    void notifyApproval(Long clawId, ApprovalResultVO result);
}