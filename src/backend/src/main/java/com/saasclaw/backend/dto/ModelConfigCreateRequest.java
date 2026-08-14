package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ModelConfigCreateRequest {

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称长度不能超过 64")
    private String name;

    @NotBlank(message = "供应商不能为空")
    @Size(max = 32, message = "供应商长度不能超过 32")
    private String provider;

    @NotBlank(message = "模型名不能为空")
    @Size(max = 64, message = "模型名长度不能超过 64")
    private String modelName;

    @NotBlank(message = "endpoint 不能为空")
    @Size(max = 255, message = "endpoint 长度不能超过 255")
    private String endpoint;

    @Size(max = 255, message = "apiKey 长度不能超过 255")
    private String apiKey;
}
