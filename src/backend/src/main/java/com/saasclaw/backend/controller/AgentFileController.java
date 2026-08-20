package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.service.AgentFileService;
import com.saasclaw.backend.vo.AgentFileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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

    /** 人格文件全量读取：返回文件全文；不存在抛 404（前端据此视为空文件可创建） */
    @GetMapping("/{fileName}")
    public Result<String> readContent(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long agentId,
                                      @PathVariable String fileName) {
        return Result.ok(agentFileService.readContent(userId, agentId, fileName));
    }

    /** 人格文件全量写入：JSON {content} 全量覆盖；仅限 SOUL/IDENTITY/AGENTS.md */
    @PutMapping("/{fileName}")
    public Result<AgentFileVO> writeContent(@RequestAttribute("userId") Long userId,
                                            @PathVariable Long agentId,
                                            @PathVariable String fileName,
                                            @RequestBody(required = false) Map<String, String> body) {
        String content = body == null ? null : body.get("content");
        return Result.ok(agentFileService.writeContent(userId, agentId, fileName, content));
    }
}