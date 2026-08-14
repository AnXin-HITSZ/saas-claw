package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.AgentInstallVO;
import com.saasclaw.backend.vo.MyAgentInstallationVO;
import com.saasclaw.backend.vo.ShopAgentVO;

import java.util.List;

public interface AgentShopService {

    /** 上架：仅自建 Agent（source='self'）可上架 */
    void publish(Long userId, Long agentId);

    void unpublish(Long userId, Long agentId);

    List<ShopAgentVO> listShop();

    /** 安装到指定 Claw（claw 必须本人所有且启用），返回本地副本 + 缺失 Skill 清单 */
    AgentInstallVO install(Long userId, Long agentId, Long clawId);

    List<MyAgentInstallationVO> listMyInstallations(Long userId);

    void uninstall(Long userId, Long installationId);
}
