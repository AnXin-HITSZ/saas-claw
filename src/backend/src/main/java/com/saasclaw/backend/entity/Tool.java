package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tool")
public class Tool {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具唯一标识 = 代码 @tool 名，由同步接口维护 */
    private String name;

    /** 工具描述（平台展示用） */
    private String description;

    /** 工具入参定义（JSON Schema 字符串） */
    private String schemaJson;

    /** 1=敏感（触发审批）0=普通 */
    private Integer isSensitive;

    /** 1=启用 0=禁用/软删 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
