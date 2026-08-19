package com.saasclaw.backend.dto;

import lombok.Data;

@Data
public class UpdateToolRequest {

    private String description;

    /** 入参定义（JSON Schema 字符串） */
    private String schemaJson;

    /** 1=敏感（触发审批）0=普通 */
    private Integer isSensitive;

    /** 1=启用 0=停用 */
    private Integer status;
}