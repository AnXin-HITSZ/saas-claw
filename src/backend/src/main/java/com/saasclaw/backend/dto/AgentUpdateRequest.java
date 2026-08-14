package com.saasclaw.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Agent 部分更新：仅可改可编辑字段，alias / clawId / source / version 不可改 */
@Data
public class AgentUpdateRequest {

    @Size(max = 64)
    private String name;

    @Size(max = 512)
    private String description;

    private String systemPrompt;

    @Size(max = 64)
    private String baseModel;

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature;

    @Min(1)
    private Integer maxTokens;
}
