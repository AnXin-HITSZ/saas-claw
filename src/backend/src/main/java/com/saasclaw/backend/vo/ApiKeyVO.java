package com.saasclaw.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** API Key 列表项：不回显明文，name 为主标识，keySuffix 兜底分辨同名 */
@Data
@AllArgsConstructor
public class ApiKeyVO {

    private Long id;

    /** 创建时用户起的名称 */
    private String name;

    /** 明文 key 末 6 位（前端拼成 sk-••••••xxxxxx） */
    private String keySuffix;

    /** 1=有效 0=已吊销 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
