package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.InstallBatchResultVO;
import com.saasclaw.backend.vo.MySkillInstallationVO;
import com.saasclaw.backend.vo.ShopSkillVO;
import com.saasclaw.backend.entity.SkillInstallation;

import java.util.List;

public interface SkillShopService {

    /** 上架自己的 Skill（仅 source='self' 自建可上架，一个 skill 只能上架一次） */
    void publish(Long userId, Long skillId);

    /** 下架：只能下架本人发布的 */
    void unpublish(Long userId, Long skillId);

    /** 商店列表：在售商品 + 发布者昵称 + 安装数 */
    List<ShopSkillVO> listShop();

    /**
     * 安装：建本地副本（source='shop'）+ 写安装记录 + installs++。
     * 若用户命名空间已有同名 skill → 409。
     */
    SkillInstallation install(Long userId, Long skillId);

    /** 我的资源池：安装记录 + 副本信息 + 被 Agent 绑定计数 */
    List<MySkillInstallationVO> listMyInstallations(Long userId);

    /**
     * 卸载：默认若副本正被 Agent 绑定则 409（回传绑定 Agent 列表）；
     * force=true 时双软删（安装记录 + 本地副本）。
     */
    void uninstall(Long userId, Long installationId, boolean force);

    /**
     * 批量安装（一键安装缺失 Skill）：逐条独立事务，单条失败不中断整批，
     * 失败项（skillId + 原因）回传前端。
     */
    InstallBatchResultVO installBatch(Long userId, List<Long> skillIds);
}