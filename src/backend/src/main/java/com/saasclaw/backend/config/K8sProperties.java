package com.saasclaw.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Claw K8s 供给配置（prefix=claw.k8s）。
 *
 * <p>backend 在创建 Claw 时按此配置向 K3s 下发 runtime Pod。所有敏感值经由 claw 命名空间内的
 * Secret 注入 Pod（跨命名空间无法直接引用 backend 自身 Secret，故由 backend 复制下发）。
 *
 * <p>enabled=false（默认）时不装配 KubernetesClient，Claw 创建退化为纯写库（本地开发行为不变）。
 * 环境变量映射示例：CLAW_K8S_ENABLED、CLAW_K8S_RUNTIME_IMAGE、CLAW_K8S_BACKEND_API_KEY 等。
 */
@Data
@Component
@ConfigurationProperties(prefix = "claw.k8s")
public class K8sProperties {

    /** 是否启用 K8s 供给。false 时 Claw 创建仅写库、不建 K8s 对象。 */
    private boolean enabled = false;

    /** runtime 镜像（含仓库与 tag），如 crpi-xxx.../anxin-hitsz-saas-claw/runtime:latest。 */
    private String runtimeImage = "";

    /** 镜像拉取策略。 */
    private String imagePullPolicy = "IfNotPresent";

    /** Claw Pod 回连 backend 的基址（含 /api）。集群内 FQDN，跨命名空间必须写全。 */
    private String backendBaseUrl = "http://backend.saas-claw.svc.cluster.local:8080/api";

    /** Claw Pod 调 backend 程序通道用的平台级 API Key（authorization 表里的 sk-xxx）。敏感。 */
    private String backendApiKey = "";

    /** Redis 连接串 redis://[:pwd@]host:port/db。敏感。 */
    private String redisUrl = "";

    private String mysqlHost = "";
    private int mysqlPort = 3306;
    private String mysqlUser = "";
    /** 敏感。 */
    private String mysqlPassword = "";
    private String mysqlDatabase = "saas_claw";

    private String ossEndpoint = "";
    /** 敏感。 */
    private String ossAccessKeyId = "";
    /** 敏感。 */
    private String ossAccessKeySecret = "";
    private String ossBucket = "";

    /** 路由模型在 model_config 表中的 name。 */
    private String routerModel = "router";

    /** Claw Pod 工作区挂载路径，须与 runtime WORKSPACE_ROOT 一致。 */
    private String workspaceRoot = "/workspace";

    /** 每个 Claw 工作区 PVC 大小。 */
    private String pvcSize = "1Gi";

    /** PVC StorageClass。K3s 自带 local-path。 */
    private String storageClass = "local-path";

    /** Pod 资源请求/限制。 */
    private String cpuRequest = "100m";
    private String cpuLimit = "1";
    private String memoryRequest = "256Mi";
    private String memoryLimit = "1Gi";
}
