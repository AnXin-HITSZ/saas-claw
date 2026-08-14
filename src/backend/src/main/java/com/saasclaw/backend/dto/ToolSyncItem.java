package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ToolSyncItem {

    @NotBlank(message = "工具名不能为空")
    @Size(max = 64, message = "工具名长度不能超过 64")
    private String name;

    @Size(max = 512, message = "描述长度不能超过 512")
    private String description;

    /** 工具入参定义（JSON Schema 字符串） */
    private String schemaJson;

    /** 1=敏感（触发审批）0=普通 */
    private Integer isSensitive;
}
