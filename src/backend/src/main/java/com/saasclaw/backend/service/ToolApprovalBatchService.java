package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.CreateApprovalBatchRequest;
import com.saasclaw.backend.dto.HandleApprovalBatchRequest;
import com.saasclaw.backend.vo.ApprovalBatchVO;

import java.util.List;

/** 批量敏感工具审批：spawn_subagent 聚合子任务敏感操作，一张审批卡覆盖多路子请求 */
public interface ToolApprovalBatchService {

    /** 创建批量审批（程序通道）：幂等键 requestId 命中返回已有记录 */
    ApprovalBatchVO create(Long userId, CreateApprovalBatchRequest request);

    /** 处理批量审批（人工通道）：整体决策 + 可选逐子请求覆盖，完成后回调 runtime 恢复主图 */
    void handle(Long userId, Long batchId, HandleApprovalBatchRequest request);

    /** 我的待审批批量列表 */
    List<ApprovalBatchVO> listPending(Long userId);

    /** 批量审批历史 */
    List<ApprovalBatchVO> listHistory(Long userId);
}
