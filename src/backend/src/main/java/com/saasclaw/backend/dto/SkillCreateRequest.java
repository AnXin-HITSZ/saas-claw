package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SkillCreateRequest {

    @NotBlank(message = "名称不能为空")
    @Size(max = 64, message = "名称长度不能超过 64")
    private String name;

    /** 路由摘要（必填） */
    @NotBlank(message = "description 不能为空")
    @Size(max = 512, message = "description 长度不能超过 512")
    private String description;

    @Size(max = 32, message = "version 长度不能超过 32")
    private String version;

    @Size(max = 64, message = "author 长度不能超过 64")
    private String author;
}