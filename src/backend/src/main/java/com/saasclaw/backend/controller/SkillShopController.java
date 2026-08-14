package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.dto.InstallSkillsRequest;
import com.saasclaw.backend.vo.InstallBatchResultVO;
import com.saasclaw.backend.vo.MySkillInstallationVO;
import com.saasclaw.backend.vo.ShopSkillVO;
import com.saasclaw.backend.entity.SkillInstallation;
import com.saasclaw.backend.service.SkillShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/shop")
@RequiredArgsConstructor
public class SkillShopController {

    private final SkillShopService skillShopService;

    @PostMapping("/skills/{skillId}/publish")
    public Result<Void> publish(@RequestAttribute("userId") Long userId,
                                @PathVariable Long skillId) {
        skillShopService.publish(userId, skillId);
        return Result.ok();
    }

    @DeleteMapping("/skills/{skillId}/publish")
    public Result<Void> unpublish(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long skillId) {
        skillShopService.unpublish(userId, skillId);
        return Result.ok();
    }

    @GetMapping("/skills")
    public Result<List<ShopSkillVO>> listShop() {
        return Result.ok(skillShopService.listShop());
    }

    @PostMapping("/skills/{skillId}/install")
    public Result<SkillInstallation> install(@RequestAttribute("userId") Long userId,
                                             @PathVariable Long skillId) {
        return Result.ok(skillShopService.install(userId, skillId));
    }

    /** 一键批量安装缺失 Skill：单条失败不中断整批，失败项回传原因 */
    @PostMapping("/skills/install-batch")
    public Result<InstallBatchResultVO> installBatch(@RequestAttribute("userId") Long userId,
                                                     @Valid @RequestBody InstallSkillsRequest request) {
        return Result.ok(skillShopService.installBatch(userId, request.getSkillIds()));
    }

    @GetMapping("/my-installations")
    public Result<List<MySkillInstallationVO>> listMyInstallations(@RequestAttribute("userId") Long userId) {
        return Result.ok(skillShopService.listMyInstallations(userId));
    }

    @DeleteMapping("/my-installations/{id}")
    public Result<Void> uninstall(@RequestAttribute("userId") Long userId,
                                  @PathVariable Long id,
                                  @RequestParam(defaultValue = "false") boolean force) {
        skillShopService.uninstall(userId, id, force);
        return Result.ok();
    }
}