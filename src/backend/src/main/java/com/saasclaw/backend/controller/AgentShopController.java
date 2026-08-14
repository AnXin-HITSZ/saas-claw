package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.dto.InstallAgentRequest;
import com.saasclaw.backend.service.AgentShopService;
import com.saasclaw.backend.vo.AgentInstallVO;
import com.saasclaw.backend.vo.MyAgentInstallationVO;
import com.saasclaw.backend.vo.ShopAgentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/shop/agents")
@RequiredArgsConstructor
public class AgentShopController {

    private final AgentShopService agentShopService;

    /** 上架：仅自建 Agent 可上架 */
    @PostMapping("/{agentId}/publish")
    public Result<Void> publish(@RequestAttribute("userId") Long userId,
                                @PathVariable Long agentId) {
        agentShopService.publish(userId, agentId);
        return Result.ok();
    }

    @DeleteMapping("/{agentId}/publish")
    public Result<Void> unpublish(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long agentId) {
        agentShopService.unpublish(userId, agentId);
        return Result.ok();
    }

    /** 商店列表 */
    @GetMapping
    public Result<List<ShopAgentVO>> listShop() {
        return Result.ok(agentShopService.listShop());
    }

    /** 安装到指定 Claw：body { clawId }，返回副本 + 缺失 Skill 清单 */
    @PostMapping("/{agentId}/install")
    public Result<AgentInstallVO> install(@RequestAttribute("userId") Long userId,
                                          @PathVariable Long agentId,
                                          @Valid @RequestBody InstallAgentRequest request) {
        return Result.ok(agentShopService.install(userId, agentId, request.getClawId()));
    }

    /** 我安装的 Agent 列表 */
    @GetMapping("/my-agent-installations")
    public Result<List<MyAgentInstallationVO>> listMyInstallations(@RequestAttribute("userId") Long userId) {
        return Result.ok(agentShopService.listMyInstallations(userId));
    }

    /** 卸载（双软删：安装记录 + 本地副本） */
    @DeleteMapping("/my-agent-installations/{installationId}")
    public Result<Void> uninstall(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long installationId) {
        agentShopService.uninstall(userId, installationId);
        return Result.ok();
    }
}
