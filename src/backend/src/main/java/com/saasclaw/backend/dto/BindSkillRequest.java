package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class BindSkillRequest {

    @NotNull(message = "skillId 不能为空")
    private Long skillId;
}