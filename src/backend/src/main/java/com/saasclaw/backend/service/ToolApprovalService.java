package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.CreateApprovalRequest;
import com.saasclaw.backend.dto.HandleApprovalRequest;
import com.saasclaw.backend.vo.ApprovalRequestVO;
import com.saasclaw.backend.vo.ApprovalResultVO;

import java.util.List;

public interface ToolApprovalService {

    /** 创建审批请求（程序通道）：幂等键 requestId 命中返回已有记录 */
    ApprovalRequestVO create(Long userId, CreateApprovalRequest request);

    /** 查询审批结果（程序通道，runtime 断线/重连后的按需查询兜底） */
    ApprovalResultVO getResult(Long userId, String requestId);

    /** 我的待审批列表（人工通道） */
    List<ApprovalRequestVO> listPending(Long userId);

    /** 审批历史（人工通道） */
    List<ApprovalRequestVO> listHistory(Long userId);

    /** 审批（人工通道）：允许/拒绝/自定义消息，完成后 Redis 广播 SSE */
    void handle(Long userId, Long approvalId, HandleApprovalRequest request);

    /** 按 requestId 审批（人工通道）：对话页审批弹窗直达，省去前端按 request_id 反查 DB id */
    void handleByRequestId(Long userId, String requestId, HandleApprovalRequest request);
}