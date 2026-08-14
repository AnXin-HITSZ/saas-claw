package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.config.RequireAdmin;
import com.saasclaw.backend.dto.SkillCreateRequest;
import com.saasclaw.backend.dto.SkillUpdateRequest;
import com.saasclaw.backend.entity.Skill;
import com.saasclaw.backend.service.SkillService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillService skillService;

    @GetMapping
    public Result<List<Skill>> list(@RequestAttribute("userId") Long userId) {
        return Result.ok(skillService.list(userId));
    }

    @PostMapping
    public Result<Skill> create(@RequestAttribute("userId") Long userId,
                                @Valid @RequestBody SkillCreateRequest request) {
        return Result.ok(skillService.create(userId, request));
    }

    @PutMapping("/{id}")
    public Result<Skill> update(@RequestAttribute("userId") Long userId,
                                @PathVariable Long id,
                                @Valid @RequestBody SkillUpdateRequest request) {
        return Result.ok(skillService.update(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        skillService.delete(userId, id);
        return Result.ok();
    }

    // ---------- 平台 Skill 管理（管理员 role=1） ----------

    @PostMapping("/platform")
    @RequireAdmin
    public Result<Skill> createPlatform(@Valid @RequestBody SkillCreateRequest request) {
        return Result.ok(skillService.createPlatform(request));
    }

    @PutMapping("/platform/{id}")
    @RequireAdmin
    public Result<Skill> updatePlatform(@PathVariable Long id,
                                        @Valid @RequestBody SkillUpdateRequest request) {
        return Result.ok(skillService.updatePlatform(id, request));
    }

    @DeleteMapping("/platform/{id}")
    @RequireAdmin
    public Result<Void> deletePlatform(@PathVariable Long id) {
        skillService.deletePlatform(id);
        return Result.ok();
    }
}