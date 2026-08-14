package com.saasclaw.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ModelConfigUpdateRequest {

    @Size(max = 255, message = "endpoint 长度不能超过 255")
    private String endpoint;

    @Size(max = 255, message = "apiKey 长度不能超过 255")
    private String apiKey;

    /** 0=禁用 1=启用；null=不更新 */
    private Integer status;
}
