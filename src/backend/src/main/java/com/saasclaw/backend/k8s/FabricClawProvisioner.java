package com.saasclaw.backend.k8s;

import com.saasclaw.backend.config.K8sProperties;
import com.saasclaw.backend.entity.Claw;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fabric8 实现：把一个 Claw 实例化为 K3s 内的 namespace/Secret/PVC/Deployment/Service。
 *
 * <p>约定（与网关路由、审批回调硬绑定，不能改名）：
 * <ul>
 *   <li>namespace = Service 名 = {@code claw-{id}}；Service 端口 8000。</li>
 *   <li>Pod 跑 runtime 镜像，单副本（runtime 审批态是进程内 dict，不可多副本）。</li>
 *   <li>敏感配置放入 claw 命名空间内的 Secret；Pod 身份等非敏感值用字面量 env。</li>
 * </ul>
 *
 * <p>失败自愈：provision 任一步抛错即删除本 Claw 命名空间（级联清理已建资源）后重抛，
 * 交由上层 {@code @Transactional} 回滚 DB 行，保证不残留孤儿命名空间。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "claw.k8s", name = "enabled", havingValue = "true")
public class FabricClawProvisioner implements ClawProvisioner {

    private static final int CLAW_PORT = 8000;
    private static final String SECRET_NAME = "claw-secret";
    private static final String PVC_NAME = "claw-workspace";

    private final KubernetesClient client;
    private final K8sProperties props;

    public FabricClawProvisioner(KubernetesClient client, K8sProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public void provision(Claw claw, String backendApiKeyPlain) {
        String ns = claw.getNamespace();
        if (props.getRuntimeImage() == null || props.getRuntimeImage().isBlank()) {
            throw new IllegalStateException("claw.k8s.runtime-image 未配置，无法下发 Claw Pod");
        }
        try {
            createNamespace(ns, claw);
            createSecret(ns, backendApiKeyPlain);
            createPvc(ns);
            createDeployment(ns, claw);
            createService(ns);
            log.info("Claw K8s 供给完成：namespace={} image={}", ns, props.getRuntimeImage());
        } catch (RuntimeException e) {
            log.error("Claw K8s 供给失败，回滚清理 namespace={}", ns, e);
            safeTeardown(ns);
            throw e;
        }
    }

    @Override
    public void teardown(String namespace) {
        // 删命名空间即级联删除其中所有资源；不存在返回 null，幂等
        client.namespaces().withName(namespace).delete();
        log.info("Claw K8s 拆除请求已提交：namespace={}", namespace);
    }

    private void safeTeardown(String ns) {
        try {
            client.namespaces().withName(ns).delete();
        } catch (RuntimeException ex) {
            log.error("回滚清理 namespace={} 失败，需人工核查", ns, ex);
        }
    }

    private void createNamespace(String ns, Claw claw) {
        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata()
                .withName(ns)
                .addToLabels("app.kubernetes.io/managed-by", "saas-claw-backend")
                .addToLabels("saas-claw/claw-id", String.valueOf(claw.getId()))
                .addToLabels("saas-claw/user-id", String.valueOf(claw.getUserId()))
                .endMetadata()
                .build();
        client.namespaces().resource(namespace).create();
    }

    /**
     * 敏感配置进 Secret（stringData 明文写入，K8s 侧存储为 base64）。
     * BACKEND_API_KEY 是「本 Claw 专属」的程序通道 key（ClawService 创建时生成并写 authorization 表），
     * 归属 claw.userId —— 程序通道/审批的身份由它解析，不可复用其它 Claw 或平台 key。
     */
    private void createSecret(String ns, String backendApiKeyPlain) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("REDIS_URL", props.getRedisUrl());
        data.put("MYSQL_PASSWORD", props.getMysqlPassword());
        data.put("OSS_ACCESS_KEY_ID", props.getOssAccessKeyId());
        data.put("OSS_ACCESS_KEY_SECRET", props.getOssAccessKeySecret());
        data.put("BACKEND_API_KEY", backendApiKeyPlain);

        Map<String, String> b64 = new LinkedHashMap<>();
        data.forEach((k, v) -> b64.put(k,
                Base64.getEncoder().encodeToString((v == null ? "" : v).getBytes(StandardCharsets.UTF_8))));

        Secret secret = new SecretBuilder()
                .withNewMetadata().withName(SECRET_NAME).withNamespace(ns).endMetadata()
                .withType("Opaque")
                .withData(b64)
                .build();
        client.secrets().inNamespace(ns).resource(secret).create();
    }

    private void createPvc(String ns) {
        PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(PVC_NAME).withNamespace(ns).endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withStorageClassName(props.getStorageClass())
                .withNewResources()
                .addToRequests("storage", new Quantity(props.getPvcSize()))
                .endResources()
                .endSpec()
                .build();
        client.persistentVolumeClaims().inNamespace(ns).resource(pvc).create();
    }

    private void createDeployment(String ns, Claw claw) {
        Map<String, String> selector = Map.of("app", ns);

        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata().withName(ns).withNamespace(ns)
                .addToLabels("app", ns)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1) // 单副本：runtime 审批态是进程内 dict
                .withNewSelector().withMatchLabels(selector).endSelector()
                .withNewTemplate()
                .withNewMetadata().withLabels(selector).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("runtime")
                .withImage(props.getRuntimeImage())
                .withImagePullPolicy(props.getImagePullPolicy())
                .addNewPort().withContainerPort(CLAW_PORT).endPort()
                .withEnv(buildEnv(claw))
                .withNewResources()
                .addToRequests("cpu", new Quantity(props.getCpuRequest()))
                .addToRequests("memory", new Quantity(props.getMemoryRequest()))
                .addToLimits("cpu", new Quantity(props.getCpuLimit()))
                .addToLimits("memory", new Quantity(props.getMemoryLimit()))
                .endResources()
                // runtime 无 /health，用 TCP 探针
                .withNewReadinessProbe()
                .withNewTcpSocket().withNewPort(CLAW_PORT).endTcpSocket()
                .withInitialDelaySeconds(5).withPeriodSeconds(10)
                .endReadinessProbe()
                .withNewLivenessProbe()
                .withNewTcpSocket().withNewPort(CLAW_PORT).endTcpSocket()
                .withInitialDelaySeconds(20).withPeriodSeconds(20)
                .endLivenessProbe()
                .addNewVolumeMount()
                .withName("workspace").withMountPath(props.getWorkspaceRoot())
                .endVolumeMount()
                .endContainer()
                .addNewVolume()
                .withName("workspace")
                .withNewPersistentVolumeClaim().withClaimName(PVC_NAME).endPersistentVolumeClaim()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        client.apps().deployments().inNamespace(ns).resource(deployment).create();
    }

    /** 非敏感值字面量 env；敏感值从 Secret 引用。变量名须与 runtime Settings 字段大写一致。 */
    private List<EnvVar> buildEnv(Claw claw) {
        List<EnvVar> env = new ArrayList<>();
        // 本 Pod 身份
        env.add(literal("CLAW_ID", String.valueOf(claw.getId())));
        env.add(literal("NAMESPACE", claw.getNamespace()));
        // backend 程序通道
        env.add(literal("BACKEND_BASE_URL", props.getBackendBaseUrl()));
        // MySQL 非敏感
        env.add(literal("MYSQL_HOST", props.getMysqlHost()));
        env.add(literal("MYSQL_PORT", String.valueOf(props.getMysqlPort())));
        env.add(literal("MYSQL_USER", props.getMysqlUser()));
        env.add(literal("MYSQL_DATABASE", props.getMysqlDatabase()));
        // OSS 非敏感
        env.add(literal("OSS_ENDPOINT", props.getOssEndpoint()));
        env.add(literal("OSS_BUCKET", props.getOssBucket()));
        // 工作区 / 路由模型
        env.add(literal("WORKSPACE_ROOT", props.getWorkspaceRoot()));
        env.add(literal("ROUTER_MODEL", props.getRouterModel()));
        // 敏感值：Secret 引用
        env.add(secretRef("REDIS_URL"));
        env.add(secretRef("MYSQL_PASSWORD"));
        env.add(secretRef("OSS_ACCESS_KEY_ID"));
        env.add(secretRef("OSS_ACCESS_KEY_SECRET"));
        env.add(secretRef("BACKEND_API_KEY"));
        return env;
    }

    private EnvVar literal(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value == null ? "" : value).build();
    }

    private EnvVar secretRef(String key) {
        return new EnvVarBuilder()
                .withName(key)
                .withNewValueFrom()
                .withNewSecretKeyRef().withName(SECRET_NAME).withKey(key).endSecretKeyRef()
                .endValueFrom()
                .build();
    }

    private void createService(String ns) {
        // Service 名必须 = namespace = claw-{id}，匹配 claw-{id}.claw-{id}.svc.cluster.local
        Service service = new ServiceBuilder()
                .withNewMetadata().withName(ns).withNamespace(ns).endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", ns))
                .addNewPort()
                .withPort(CLAW_PORT).withNewTargetPort(CLAW_PORT).withProtocol("TCP")
                .endPort()
                .withType("ClusterIP")
                .endSpec()
                .build();
        client.services().inNamespace(ns).resource(service).create();
    }
}
