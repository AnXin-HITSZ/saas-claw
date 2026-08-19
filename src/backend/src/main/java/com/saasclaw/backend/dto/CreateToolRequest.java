package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateToolRequest {

    /** 工具唯一标识（须与 runtime 已注册的 @tool 名一致，否则不可执行） */
    @NotBlank(message = "工具名不能为空")
    private String name;

    private String description;

    /** 入参定义（JSON Schema 字符串） */
    private String schemaJson;

    /** 1=敏感（触发审批）0=普通 */
    private Integer isSensitive;

    /** 1=启用 0=停用 */
    private Integer status;
}