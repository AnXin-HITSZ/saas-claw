package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 审批操作（人工通道，用户确认） */
@Data
public class HandleApprovalRequest {

    /** 1=允许 2=拒绝 3=自定义消息 */
    @NotNull(message = "action 不能为空")
    private Integer action;

    /** action=3 时必填（Service 层校验） */
    @Size(max = 512, message = "customMessage 过长")
    private String customMessage;
}