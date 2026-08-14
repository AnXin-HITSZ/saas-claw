package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.dto.ClawCreateRequest;
import com.saasclaw.backend.entity.Claw;
import com.saasclaw.backend.service.ClawService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/claws")
@RequiredArgsConstructor
public class ClawController {

    private final ClawService clawService;

    @GetMapping
    public Result<List<Claw>> list(@RequestAttribute("userId") Long userId) {
        return Result.ok(clawService.list(userId));
    }

    @PostMapping
    public Result<Claw> create(@RequestAttribute("userId") Long userId,
                               @Valid @RequestBody ClawCreateRequest request) {
        return Result.ok(clawService.create(userId, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        clawService.delete(userId, id);
        return Result.ok();
    }
}