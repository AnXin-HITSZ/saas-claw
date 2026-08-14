package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Agent 人格文件（AGENTS.md / IDENTITY.md / SOUL.md） */
@Data
@TableName("agent_file")
public class AgentFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    /** 相对路径 */
    private String fileName;

    /** OSS 公开读 URL */
    private String fileUrl;

    private String fileType;

    private Long fileSize;

    private String fileHash;

    private LocalDateTime createdAt;
}