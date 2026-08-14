package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** agent_shop：Agent 商店上架记录（一个源 Agent 一条） */
@Data
@TableName("agent_shop")
public class AgentShop {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源 Agent id（发布者本人的 Agent） */
    private Long agentId;

    /** 发布者 user id */
    private Long publisherId;

    /** 累计安装数 */
    private Integer installs;

    /** 1=上架中 0=已下架 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
