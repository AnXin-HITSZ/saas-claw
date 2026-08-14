package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.dto.AgentCreateRequest;
import com.saasclaw.backend.dto.AgentUpdateRequest;
import com.saasclaw.backend.dto.BindSkillRequest;
import com.saasclaw.backend.entity.Agent;
import com.saasclaw.backend.entity.Skill;
import com.saasclaw.backend.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @GetMapping
    public Result<List<Agent>> list(@RequestAttribute("userId") Long userId,
                                    @RequestParam(required = false) Long clawId) {
        return Result.ok(agentService.list(userId, clawId));
    }

    @PostMapping
    public Result<Agent> create(@RequestAttribute("userId") Long userId,
                                @Valid @RequestBody AgentCreateRequest request) {
        return Result.ok(agentService.create(userId, request));
    }

    @PutMapping("/{id}")
    public Result<Agent> update(@RequestAttribute("userId") Long userId,
                                @PathVariable Long id,
                                @Valid @RequestBody AgentUpdateRequest request) {
        return Result.ok(agentService.update(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@RequestAttribute("userId") Long userId, @PathVariable Long id) {
        agentService.delete(userId, id);
        return Result.ok();
    }

    // ---------- Agent-Skill 绑定 ----------

    @GetMapping("/{agentId}/skills")
    public Result<List<Skill>> listSkills(@RequestAttribute("userId") Long userId,
                                          @PathVariable Long agentId) {
        return Result.ok(agentService.listSkills(userId, agentId));
    }

    @PostMapping("/{agentId}/skills")
    public Result<Void> bindSkill(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long agentId,
                                  @Valid @RequestBody BindSkillRequest request) {
        agentService.bindSkill(userId, agentId, request.getSkillId());
        return Result.ok();
    }

    @DeleteMapping("/{agentId}/skills/{skillId}")
    public Result<Void> unbindSkill(@RequestAttribute("userId") Long userId,
                                    @PathVariable Long agentId,
                                    @PathVariable Long skillId) {
        agentService.unbindSkill(userId, agentId, skillId);
        return Result.ok();
    }
}
