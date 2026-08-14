package com.saasclaw.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateApiKeyVO {

    private Long id;

    /** 明文 key，仅创建时返回一次 */
    private String apiKey;

    /** 创建时用户起的名称 */
    private String name;

    /** 明文 key 末 6 位（同名时辅助分辨） */
    private String keySuffix;

    private LocalDateTime createdAt;
}
