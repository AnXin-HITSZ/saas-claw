package com.saasclaw.backend.entity;

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

    /** 所属用户 */
    private Long userId;

    /** 显示名（同一用户下唯一） */
    private String name;

    /** K3s namespace 名，命名规则 claw-{id}，全局唯一 */
    private String namespace;

    /** 1=启用 0=禁用/软删 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
