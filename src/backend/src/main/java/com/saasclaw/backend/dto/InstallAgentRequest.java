package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 安装 Agent 请求体 */
@Data
public class InstallAgentRequest {

    /** 安装目标 Claw id */
    @NotNull(message = "clawId 不能为空")
    private Long clawId;
}
