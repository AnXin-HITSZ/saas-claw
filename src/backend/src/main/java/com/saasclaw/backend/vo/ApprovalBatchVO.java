package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 批量审批展示项（用户侧：pending / 历史 / 创建返回共用） */
@Data
public class ApprovalBatchVO {

    private Long batchId;
    private String requestId;
    private Long agentId;
    private String agentName;
    private Long clawId;
    private List<SubRequestVO> subRequests;
    /** 0=待审批 1=已处理 */
    private Integer status;
    /** 整体决策 1=允许 2=拒绝 3=自定义消息（未处理时为 null） */
    private Integer action;
    private String customMessage;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;

    @Data
    public static class SubRequestVO {
        private String requestId;
        private Long toolId;
        private String toolName;
        private String inputSummary;
    }
}
