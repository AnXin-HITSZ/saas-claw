package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApiKeyRequest {

    /** API Key 名称（用户起的语义化标签，列表主标识，可重名） */
    @NotBlank
    @Size(max = 64)
    private String name;
}
