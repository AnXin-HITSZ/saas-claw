package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 审批结果（程序通道：runtime SSE 推送 + GET 查询兜底共用） */
@Data
public class ApprovalResultVO {

    private Long approvalId;
    private String requestId;
    /** 0=待审批 1=已处理 */
    private Integer status;
    /** 1=允许 2=拒绝 3=自定义消息（待审批时 null） */
    private Integer action;
    private String customMessage;
    private LocalDateTime handledAt;
}
