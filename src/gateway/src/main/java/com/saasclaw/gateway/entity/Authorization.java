package com.saasclaw.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("authorization")
public class Authorization {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 存 SHA-256 哈希后的 sk-xxx，永不存明文 */
    private String apiKey;

    private String name;

    private String keySuffix;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}