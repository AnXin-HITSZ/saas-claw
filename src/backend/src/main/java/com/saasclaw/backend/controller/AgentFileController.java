package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.service.AgentFileService;
import com.saasclaw.backend.vo.AgentFileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/agents/{agentId}/files")
@RequiredArgsConstructor
public class AgentFileController {

    private final AgentFileService agentFileService;

    /** 上传：multipart，file 为文件内容，path 为相对路径（如 AGENTS.md） */
    @PostMapping
    public Result<AgentFileVO> upload(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long agentId,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam("path") String path) {
        return Result.ok(agentFileService.upload(userId, agentId, path, file));
    }

    @GetMapping
    public Result<List<AgentFileVO>> list(@RequestAttribute("userId") Long userId,
                                          @PathVariable Long agentId) {
        return Result.ok(agentFileService.list(userId, agentId));
    }

    @DeleteMapping("/{fileId}")
    public Result<Void> delete(@RequestAttribute("userId") Long userId,
                               @PathVariable Long agentId,
                               @PathVariable Long fileId) {
        agentFileService.delete(userId, agentId, fileId);
        return Result.ok();
    }
}