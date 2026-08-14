package com.saasclaw.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** Skill 部分更新：source / user_id 不可改 */
@Data
public class SkillUpdateRequest {

    @Size(max = 64)
    private String name;

    @Size(max = 512)
    private String description;

    @Size(max = 32)
    private String version;

    @Size(max = 64)
    private String author;
}