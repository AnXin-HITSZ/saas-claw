package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** Skill 文件列表项 */
@Data
public class SkillFileVO {

    private Long id;

    /** 相对路径（SKILL.md / code/run.py） */
    private String fileName;

    /** OSS 公开读 URL */
    private String fileUrl;

    private String fileType;

    private Long fileSize;

    private String fileHash;

    private LocalDateTime createdAt;
}