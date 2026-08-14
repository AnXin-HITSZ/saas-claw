package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Skill 脚本文件（uk_skill_file：同一 skill 下文件名唯一） */
@Data
@TableName("skill_file")
public class SkillFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long skillId;

    /** 相对路径（含 SKILL.md 标准入口 + 脚本，如 SKILL.md、code/run.py） */
    private String fileName;

    /** OSS 公开读 URL */
    private String fileUrl;

    /** 文件类型（扩展名） */
    private String fileType;

    /** 字节数 */
    private Long fileSize;

    /** SHA-256，校验/去重 */
    private String fileHash;

    private LocalDateTime createdAt;
}