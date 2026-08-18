package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.ClawCreateRequest;
import com.saasclaw.backend.entity.Claw;
import com.saasclaw.backend.k8s.ClawProvisioner;
import com.saasclaw.backend.mapper.ClawMapper;
import com.saasclaw.backend.service.ClawService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClawServiceImpl implements ClawService {

    private final ClawMapper clawMapper;
    private final ClawProvisioner clawProvisioner;

    @Override
    public List<Claw> list(Long userId) {
        return clawMapper.selectList(
                new LambdaQueryWrapper<Claw>()
                        .eq(Claw::getUserId, userId)
                        .eq(Claw::getStatus, 1)
                        .orderByDesc(Claw::getId)
        );
    }

    @Override
    @Transactional
    public Claw create(Long userId, ClawCreateRequest request) {
        // name 用户级唯一校验：(user_id, name) 组合
        Long count = clawMapper.selectCount(
                new LambdaQueryWrapper<Claw>()
                        .eq(Claw::getUserId, userId)
                        .eq(Claw::getName, request.getName())
        );
        if (count > 0) {
            throw new BizException(409, "Claw 名称已存在");
        }

        // 两步写库：先占位插入拿自增 id → 回填真实 namespace
        // 占位必须唯一（uk_namespace 全局唯一约束），故用 UUID 而非固定串
        Claw claw = new Claw();
        claw.setUserId(userId);
        claw.setName(request.getName());
        claw.setNamespace(UUID.randomUUID().toString());
        claw.setStatus(1);
        clawMapper.insert(claw);

        claw.setNamespace("claw-" + claw.getId());
        clawMapper.updateById(claw);

        // K8s 供给：建 namespace / Secret / PVC / Deployment / Service。
        // 失败时 provisioner 自行清理已建资源并抛出，触发本方法 @Transactional 回滚，
        // 避免残留孤儿命名空间与脏库行。enabled=false 时为 Noop（纯写库），本地/无集群行为不变。
        clawProvisioner.provision(claw);

        return clawMapper.selectById(claw.getId());
    }

    @Override
    @Transactional
    public void delete(Long userId, Long id) {
        Claw claw = getOwned(userId, id);

        clawMapper.update(null,
                new LambdaUpdateWrapper<Claw>()
                        .eq(Claw::getId, id)
                        .set(Claw::getStatus, 0)
        );

        // 拆除 K8s 资源（删除命名空间即级联）。幂等：不存在视为成功。
        clawProvisioner.teardown(claw.getNamespace());
    }

    /**
     * 归属校验：不存在 / 已软删 / 不属于当前用户，一律视为 404。
     * 故意不区分"不存在"和"别人的"，避免泄露资源存在性。
     */
    private Claw getOwned(Long userId, Long id) {
        Claw claw = clawMapper.selectById(id);
        if (claw == null || claw.getStatus() == 0 || !claw.getUserId().equals(userId)) {
            throw new BizException(404, "Claw 不存在");
        }
        return claw;
    }
}
