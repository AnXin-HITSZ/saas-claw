package com.saasclaw.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 路由模型（router）更新请求。
 * 语义：null = 不修改；router 行不存在时视为创建，需 provider/model_name/endpoint/api_key 全填。
 */
@Data
public class RouterConfigUpdateRequest {

    @Size(max = 32, message = "供应商长度不能超过 32")
    private String provider;

    @Size(max = 64, message = "模型名长度不能超过 64")
    private String modelName;

    @Size(max = 255, message = "endpoint 长度不能超过 255")
    private String endpoint;

    @Size(max = 255, message = "apiKey 长度不能超过 255")
    private String apiKey;

    /** 0=禁用 1=启用；null=不更新 */
    private Integer status;
}
