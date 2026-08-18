package com.saasclaw.backend.k8s;

import com.saasclaw.backend.entity.Claw;

/**
 * Claw 的 K8s 供给：把一个 Claw 实例化为集群内的一套资源
 * （namespace / Secret / PVC / Deployment / Service）。
 *
 * <p>两种实现：{@link NoopClawProvisioner}（claw.k8s.enabled=false，纯写库）、
 * {@link FabricClawProvisioner}（enabled=true，真实下发）。
 */
public interface ClawProvisioner {

    /**
     * 为 Claw 下发全套 K8s 资源。命名空间与 Service 均命名为 {@code claw-{id}}，
     * 以匹配网关路由与审批回调对 {@code claw-{id}.claw-{id}.svc.cluster.local:8000} 的硬约定。
     *
     * <p>失败时实现方须自行清理已建的部分资源并抛异常，交由上层事务回滚 DB 行，避免残留命名空间。
     */
    void provision(Claw claw);

    /** 拆除 Claw 的全部 K8s 资源（删除命名空间即级联）。幂等：不存在视为成功。 */
    void teardown(String namespace);
}
