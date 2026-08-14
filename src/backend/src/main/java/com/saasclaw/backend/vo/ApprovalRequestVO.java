package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 审批请求展示项（用户侧：pending / 历史 / 创建返回共用） */
@Data
public class ApprovalRequestVO {

    private Long approvalId;
    private String requestId;
    private Long agentId;
    private String agentName;
    private Long clawId;
    private Long toolId;
    private String toolName;
    private String inputSummary;
    /** 0=待审批 1=已处理 */
    private Integer status;
    /** 1=允许 2=拒绝 3=自定义消息（未处理时为 null） */
    private Integer action;
    private String customMessage;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}