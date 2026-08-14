package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.dto.CreateApprovalRequest;
import com.saasclaw.backend.dto.ToolSyncItem;
import com.saasclaw.backend.entity.Tool;
import com.saasclaw.backend.service.ApprovalEventBus;
import com.saasclaw.backend.service.ToolApprovalService;
import com.saasclaw.backend.service.ToolService;
import com.saasclaw.backend.vo.ApprovalRequestVO;
import com.saasclaw.backend.vo.ApprovalResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;
    private final ToolApprovalService toolApprovalService;
    private final ApprovalEventBus approvalEventBus;

    /** 登录用户可看（AuthInterceptor 兜底 JWT 鉴权） */
    @GetMapping
    public Result<List<Tool>> list() {
        return Result.ok(toolService.list());
    }

    /** 程序通道：Claw Pod 同步工具（ApiKeyInterceptor 兜底 API Key 鉴权） */
    @PostMapping("/sync")
    public Result<Void> sync(@Valid @RequestBody List<ToolSyncItem> items) {
        toolService.sync(items);
        return Result.ok();
    }

    // ---------- 敏感工具审批（程序通道，API Key） ----------

    /** 创建审批请求（requestId 幂等，命中返回已有记录） */
    @PostMapping("/approval-requests")
    public Result<ApprovalRequestVO> createApproval(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody CreateApprovalRequest request) {
        return Result.ok(toolApprovalService.create(userId, request));
    }

    /** 按需查询审批结果（runtime 断线/重连兜底） */
    @GetMapping("/approval-requests/{requestId}")
    public Result<ApprovalResultVO> getApprovalResult(
            @RequestAttribute("userId") Long userId,
            @PathVariable String requestId) {
        return Result.ok(toolApprovalService.getResult(userId, requestId));
    }

    /** 挂起 SSE，等审批完成实时推送 */
    @GetMapping(value = "/approval-requests/{requestId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeApproval(
            @RequestAttribute("userId") Long userId,
            @PathVariable String requestId) {
        // 归属校验：不存在/非本人 → 404，不挂空连接
        toolApprovalService.getResult(userId, requestId);
        return approvalEventBus.subscribe(requestId);
    }
}
