package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/** 一键批量安装 Skill 请求体 */
@Data
public class InstallSkillsRequest {

    @NotEmpty(message = "skillIds 不能为空")
    private List<Long> skillIds;
}
