package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.dto.HandleApprovalBatchRequest;
import com.saasclaw.backend.dto.HandleApprovalRequest;
import com.saasclaw.backend.service.ToolApprovalBatchService;
import com.saasclaw.backend.service.ToolApprovalService;
import com.saasclaw.backend.vo.ApprovalBatchVO;
import com.saasclaw.backend.vo.ApprovalRequestVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ToolApprovalService toolApprovalService;
    private final ToolApprovalBatchService toolApprovalBatchService;

    /** 我的待审批列表 */
    @GetMapping("/pending")
    public Result<List<ApprovalRequestVO>> listPending(@RequestAttribute("userId") Long userId) {
        return Result.ok(toolApprovalService.listPending(userId));
    }

    /** 审批历史（最近 100 条） */
    @GetMapping("/history")
    public Result<List<ApprovalRequestVO>> listHistory(@RequestAttribute("userId") Long userId) {
        return Result.ok(toolApprovalService.listHistory(userId));
    }

    /** 处理审批：1=允许 2=拒绝 3=自定义消息 */
    @PostMapping("/{approvalId}/handle")
    public Result<Void> handle(@RequestAttribute("userId") Long userId,
                               @PathVariable Long approvalId,
                               @Valid @RequestBody HandleApprovalRequest request) {
        toolApprovalService.handle(userId, approvalId, request);
        return Result.ok();
    }

    /** 处理审批（按 requestId 直达）：对话页审批弹窗使用，省去前端按 request_id 反查 DB id */
    @PostMapping("/by-request/{requestId}/handle")
    public Result<Void> handleByRequest(@RequestAttribute("userId") Long userId,
                                        @PathVariable String requestId,
                                        @Valid @RequestBody HandleApprovalRequest request) {
        toolApprovalService.handleByRequestId(userId, requestId, request);
        return Result.ok();
    }

    // ---------- 批量审批（spawn_subagent 聚合，一张卡覆盖多路子请求） ----------

    /** 我的待审批批量列表 */
    @GetMapping("/batches/pending")
    public Result<List<ApprovalBatchVO>> listPendingBatches(@RequestAttribute("userId") Long userId) {
        return Result.ok(toolApprovalBatchService.listPending(userId));
    }

    /** 批量审批历史 */
    @GetMapping("/batches/history")
    public Result<List<ApprovalBatchVO>> listBatchHistory(@RequestAttribute("userId") Long userId) {
        return Result.ok(toolApprovalBatchService.listHistory(userId));
    }

    /** 处理批量审批：整体决策 + 可选逐子请求覆盖 */
    @PostMapping("/batches/{batchId}/handle")
    public Result<Void> handleBatch(@RequestAttribute("userId") Long userId,
                                    @PathVariable Long batchId,
                                    @Valid @RequestBody HandleApprovalBatchRequest request) {
        toolApprovalBatchService.handle(userId, batchId, request);
        return Result.ok();
    }

    /** 处理批量审批（按 requestId 直达）：对话页审批弹窗使用，省去前端按 request_id 反查 DB id */
    @PostMapping("/batches/by-request/{requestId}/handle")
    public Result<Void> handleBatchByRequest(@RequestAttribute("userId") Long userId,
                                             @PathVariable String requestId,
                                             @Valid @RequestBody HandleApprovalBatchRequest request) {
        toolApprovalBatchService.handleByRequestId(userId, requestId, request);
        return Result.ok();
    }
}
