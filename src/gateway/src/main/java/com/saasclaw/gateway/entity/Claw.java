package com.saasclaw.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("claw")
public class Claw {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** K3s namespace 名，命名规则 claw-{id}，全局唯一 */
    private String namespace;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}