package com.saasclaw.backend.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentCreateRequest {

    @NotNull(message = "clawId 不能为空")
    private Long clawId;

    @NotBlank(message = "alias 不能为空")
    @Size(max = 64, message = "alias 长度不能超过 64")
    private String alias;

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称长度不能超过 64")
    private String name;

    @Size(max = 512, message = "description 长度不能超过 512")
    private String description;

    private String systemPrompt;

    @NotBlank(message = "baseModel 不能为空")
    @Size(max = 64, message = "baseModel 长度不能超过 64")
    private String baseModel;

    @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
    private Double temperature;

    @Min(value = 1, message = "maxTokens 不能小于 1")
    private Integer maxTokens;
}
