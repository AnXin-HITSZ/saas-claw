package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.entity.Skill;
import com.saasclaw.backend.entity.SkillFile;
import com.saasclaw.backend.mapper.SkillFileMapper;
import com.saasclaw.backend.mapper.SkillMapper;
import com.saasclaw.backend.service.OssService;
import com.saasclaw.backend.service.SkillFileService;
import com.saasclaw.backend.vo.SkillFileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.saasclaw.backend.util.HashUtil;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SkillFileServiceImpl implements SkillFileService {

    private final SkillFileMapper skillFileMapper;
    private final SkillMapper skillMapper;
    private final OssService ossService;

    @Override
    public SkillFileVO upload(Long userId, Long skillId, String path, MultipartFile file) {
        getOwnedSkill(userId, skillId);
        validatePath(path);
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件内容为空");
        }

        String hash;
        String url;
        try {
            hash = HashUtil.sha256Hex(file.getBytes());
            // key = skill/{skillId}/{path}，同名覆盖同一对象
            url = ossService.upload("skill/" + skillId + "/" + path,
                    file.getInputStream(), file.getSize(), contentType(file, path));
        } catch (IOException e) {
            throw new BizException(500, "读取文件失败");
        }

        // upsert：uk(skill_id, file_name) 命中则覆盖更新，否则插入
        SkillFile existing = skillFileMapper.selectOne(
                new LambdaQueryWrapper<SkillFile>()
                        .eq(SkillFile::getSkillId, skillId)
                        .eq(SkillFile::getFileName, path));
        SkillFile saved;
        if (existing == null) {
            SkillFile f = new SkillFile();
            f.setSkillId(skillId);
            f.setFileName(path);
            f.setFileUrl(url);
            f.setFileType(fileTypeOf(path));
            f.setFileSize(file.getSize());
            f.setFileHash(hash);
            skillFileMapper.insert(f);
            saved = f;
        } else {
            existing.setFileUrl(url);
            existing.setFileType(fileTypeOf(path));
            existing.setFileSize(file.getSize());
            existing.setFileHash(hash);
            skillFileMapper.updateById(existing);
            saved = existing;
        }
        return toVO(saved);
    }

    @Override
    public List<SkillFileVO> list(Long userId, Long skillId) {
        getOwnedSkill(userId, skillId);
        return skillFileMapper.selectList(
                        new LambdaQueryWrapper<SkillFile>()
                                .eq(SkillFile::getSkillId, skillId)
                                .orderByAsc(SkillFile::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public void delete(Long userId, Long skillId, Long fileId) {
        getOwnedSkill(userId, skillId);
        SkillFile file = skillFileMapper.selectOne(
                new LambdaQueryWrapper<SkillFile>()
                        .eq(SkillFile::getId, fileId)
                        .eq(SkillFile::getSkillId, skillId));
        if (file == null) {
            throw new BizException(404, "文件不存在");
        }
        skillFileMapper.deleteById(file.getId());
        ossService.delete("skill/" + skillId + "/" + file.getFileName());
    }

    // ---- helpers ----

    /** 归属校验：skill 必须本人所有且启用（平台 skill 不能由用户传文件） */
    private Skill getOwnedSkill(Long userId, Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null || skill.getStatus() == 0 || !skill.getUserId().equals(userId)) {
            throw new BizException(404, "Skill 不存在");
        }
        return skill;
    }

    /** path 即 file_name（相对路径），防绝对路径 / 反斜杠 / 目录穿越 */
    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(400, "path 不能为空");
        }
        if (path.length() > 128) {
            throw new BizException(400, "path 过长");
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains("..")) {
            throw new BizException(400, "path 不合法");
        }
    }

    /** 优先用前端声明的 contentType，非 octet-stream 时按扩展名兜底推断 */
    private String contentType(MultipartFile file, String path) {
        String declared = file.getContentType();
        if (declared != null && !declared.equalsIgnoreCase("application/octet-stream")) {
            return declared;
        }
        return switch (fileTypeOf(path) == null ? "" : fileTypeOf(path)) {
            case "md" -> "text/markdown; charset=utf-8";
            case "py" -> "text/x-python; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "yaml", "yml" -> "text/yaml; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "html", "htm" -> "text/html; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    /** 取相对路径的扩展名（去点小写），如 SKILL.md → md */
    private String fileTypeOf(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        String ext = path.substring(idx + 1).toLowerCase();
        return ext.length() > 16 ? ext.substring(0, 16) : ext;
    }

    private SkillFileVO toVO(SkillFile f) {
        SkillFileVO vo = new SkillFileVO();
        vo.setId(f.getId());
        vo.setFileName(f.getFileName());
        vo.setFileUrl(f.getFileUrl());
        vo.setFileType(f.getFileType());
        vo.setFileSize(f.getFileSize());
        vo.setFileHash(f.getFileHash());
        vo.setCreatedAt(f.getCreatedAt());
        return vo;
    }
}