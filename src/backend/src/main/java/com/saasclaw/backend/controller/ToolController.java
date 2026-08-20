package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.config.RequireAdmin;
import com.saasclaw.backend.dto.CreateApprovalBatchRequest;
import com.saasclaw.backend.dto.CreateApprovalRequest;
import com.saasclaw.backend.dto.CreateToolRequest;
import com.saasclaw.backend.dto.UpdateToolRequest;
import com.saasclaw.backend.entity.Tool;
import com.saasclaw.backend.service.ApprovalEventBus;
import com.saasclaw.backend.service.ToolApprovalBatchService;
import com.saasclaw.backend.service.ToolApprovalService;
import com.saasclaw.backend.service.ToolService;
import com.saasclaw.backend.vo.ApprovalBatchVO;
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
    private final ToolApprovalBatchService toolApprovalBatchService;
    private final ApprovalEventBus approvalEventBus;

    /** 启用工具清单（登录用户可看，AuthInterceptor 兜底 JWT 鉴权） */
    @GetMapping
    public Result<List<Tool>> list() {
        return Result.ok(toolService.list());
    }

    // ---------- 工具配置（管理员 role=1，前端工具页管理） ----------

    /** 全部工具（含停用），供管理页展示 */
    @GetMapping("/all")
    @RequireAdmin
    public Result<List<Tool>> listAll() {
        return Result.ok(toolService.listAll());
    }

    @PostMapping
    @RequireAdmin
    public Result<Tool> create(@Valid @RequestBody CreateToolRequest request) {
        return Result.ok(toolService.create(request));
    }

    @PutMapping("/{id}")
    @RequireAdmin
    public Result<Tool> update(@PathVariable Long id,
                               @Valid @RequestBody UpdateToolRequest request) {
        return Result.ok(toolService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequireAdmin
    public Result<Void> remove(@PathVariable Long id) {
        toolService.remove(id);
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

    /** 创建批量审批请求（spawn_subagent 聚合子任务敏感操作，requestId 幂等） */
    @PostMapping("/approval-batches")
    public Result<ApprovalBatchVO> createApprovalBatch(
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody CreateApprovalBatchRequest request) {
        return Result.ok(toolApprovalBatchService.create(userId, request));
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