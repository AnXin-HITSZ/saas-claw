package com.saasclaw.backend.vo;

import lombok.Data;

/** 批量安装失败项 */
@Data
public class BatchFailItemVO {

    private Long skillId;
    private String reason;
}
