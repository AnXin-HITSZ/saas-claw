package com.saasclaw.backend.k8s;

import com.saasclaw.backend.entity.Claw;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 无操作供给：claw.k8s.enabled=false（默认）时生效。
 * 保持本地/无集群环境下 Claw 创建为纯写库行为，不接触 K8s API。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "claw.k8s", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopClawProvisioner implements ClawProvisioner {

    @Override
    public void provision(Claw claw) {
        log.info("claw.k8s.enabled=false，跳过 K8s 供给：claw id={} namespace={}", claw.getId(), claw.getNamespace());
    }

    @Override
    public void teardown(String namespace) {
        log.info("claw.k8s.enabled=false，跳过 K8s 拆除：namespace={}", namespace);
    }
}
