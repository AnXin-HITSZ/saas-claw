package com.saasclaw.backend.k8s;

import com.saasclaw.backend.config.K8sProperties;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * fabric8 KubernetesClient 装配。
 *
 * <p>仅当 claw.k8s.enabled=true 时创建 Bean——backend Pod 内运行时，fabric8 自动读取
 * ServiceAccount 的 in-cluster 配置（token + CA + API server 地址），无需 kubeconfig。
 * enabled=false（本地/无集群）时不装配，Claw 创建退化为纯写库。
 */
@Configuration
@ConditionalOnProperty(prefix = "claw.k8s", name = "enabled", havingValue = "true")
public class KubernetesClientConfig {

    /** in-cluster 配置：backend Pod 挂载的 ServiceAccount 决定权限（见 K3s RBAC）。 */
    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
