package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.service.SkillFileService;
import com.saasclaw.backend.vo.SkillFileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/skills/{skillId}/files")
@RequiredArgsConstructor
public class SkillFileController {

    private final SkillFileService skillFileService;

    /** 上传：multipart，file 为文件内容，path 为相对路径（如 SKILL.md、code/run.py） */
    @PostMapping
    public Result<SkillFileVO> upload(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long skillId,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam("path") String path) {
        return Result.ok(skillFileService.upload(userId, skillId, path, file));
    }

    @GetMapping
    public Result<List<SkillFileVO>> list(@RequestAttribute("userId") Long userId,
                                          @PathVariable Long skillId) {
        return Result.ok(skillFileService.list(userId, skillId));
    }

    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@RequestAttribute("userId") Long userId,
                               @PathVariable Long skillId,
                               @PathVariable Long fileId) {
        skillFileService.delete(userId, skillId, fileId);
        return Result.ok();
    }
}