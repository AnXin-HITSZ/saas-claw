package com.saasclaw.backend.entity;

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

    /** 创建时用户起的名称（列表主标识，可重名） */
    private String name;

    /** 明文 key 末 6 位（同名时辅助分辨，不回显明文） */
    private String keySuffix;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}