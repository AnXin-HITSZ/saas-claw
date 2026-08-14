package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private String nickname;

    private String email;

    private String avatarUrl;

    private Long orgId;

    private Integer status;

    /** 0=普通用户 1=管理员 */
    private Integer role;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
