package com.saasclaw.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 创建批量审批请求（程序通道，spawn_subagent 聚合子任务敏感操作后一次提交） */
@Data
public class CreateApprovalBatchRequest {

    /** 幂等键：approval:batch:{spawn_id} */
    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    /** 发起 spawn 的父 Agent */
    @NotNull(message = "agentId 不能为空")
    private Long agentId;

    @NotNull(message = "clawId 不能为空")
    private Long clawId;

    /** 子请求明细 */
    @NotEmpty(message = "subRequests 不能为空")
    private List<@Valid SubRequest> subRequests;

    @Data
    public static class SubRequest {
        /** 子请求幂等键：approval:{tool_call_id}（batch 内唯一，逐子请求决策映射键） */
        @NotBlank(message = "子请求 requestId 不能为空")
        private String requestId;
        private Long agentId;
        private Long toolId;
        private String toolName;
        private String inputSummary;
    }
}
