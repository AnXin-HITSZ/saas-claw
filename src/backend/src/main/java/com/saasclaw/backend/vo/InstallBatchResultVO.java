package com.saasclaw.backend.vo;

import com.saasclaw.backend.entity.SkillInstallation;
import lombok.Data;

import java.util.List;

/** 批量安装结果：单条失败不中断整批 */
@Data
public class InstallBatchResultVO {

    private List<SkillInstallation> succeeded;
    private List<BatchFailItemVO> failed;
}
