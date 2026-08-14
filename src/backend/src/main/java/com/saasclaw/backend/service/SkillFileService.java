package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.SkillFileVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface SkillFileService {

    /**
     * 上传文件到 Skill（同名覆盖更新）。
     * 校验 skill 归属 + path 合法性，SHA-256 入库。
     */
    SkillFileVO upload(Long userId, Long skillId, String path, MultipartFile file);

    /** Skill 文件列表（需 skill 归属） */
    List<SkillFileVO> list(Long userId, Long skillId);

    /** 删除文件（校验 skill 归属 + 文件归属，OSS + 记录双删） */
    void delete(Long userId, Long skillId, Long fileId);
}