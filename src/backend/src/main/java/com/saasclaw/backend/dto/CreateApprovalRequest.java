package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 创建敏感工具审批请求（程序通道，runtime 发起） */
@Data
public class CreateApprovalRequest {

    /** 幂等键：runtime 调用链关联 ID，重复提交返回已有记录 */
    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    @NotNull(message = "agentId 不能为空")
    private Long agentId;

    @NotNull(message = "clawId 不能为空")
    private Long clawId;

    @NotNull(message = "toolId 不能为空")
    private Long toolId;

    /** 入参摘要（展示给用户的关键信息） */
    @NotBlank(message = "inputSummary 不能为空")
    private String inputSummary;
}