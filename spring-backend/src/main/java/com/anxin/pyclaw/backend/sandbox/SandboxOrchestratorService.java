package com.anxin.pyclaw.backend.sandbox;

import com.anxin.pyclaw.backend.config.PyclawSandboxProperties;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.SeccompProfileBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SandboxOrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(SandboxOrchestratorService.class);
    private static final String APP_LABEL = "app.kubernetes.io/name";
    private static final String PART_OF_LABEL = "app.kubernetes.io/part-of";
    private static final String COMPONENT_LABEL = "app.kubernetes.io/component";
    private static final String USER_LABEL = "pyclaw.io/owner-user-id";
    private static final String CLAW_LABEL = "pyclaw.io/claw-id";

    private final ObjectProvider<KubernetesClient> clientProvider;
    private final PyclawSandboxProperties properties;

    public SandboxOrchestratorService(ObjectProvider<KubernetesClient> clientProvider, PyclawSandboxProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String namespaceForUser(String userId) {
        return dnsName(properties.getNamespacePrefix() + "-" + userId);
    }

    public void ensureUserNamespace(String userId, String username) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        Map<String, String> labels = baseUserLabels(userId);
        labels.put("pyclaw.io/username", dnsLabel(username));
        client().namespaces().resource(new NamespaceBuilder()
                .withNewMetadata()
                .withName(namespace)
                .withLabels(labels)
                .endMetadata()
                .build()).serverSideApply();
        ensureImagePullSecret(namespace);
        ensureSandboxServiceAccount(namespace);
        ensureResourceQuota(namespace);
        ensureLimitRange(namespace);
        ensureNetworkPolicies(namespace);
        log.info("ensured sandbox namespace: namespace={} user_id={}", namespace, userId);
    }

    public void ensureClawSandbox(String userId, String username, String clawId, String clawName) {
        if (!properties.isEnabled()) {
            return;
        }
        if (blank(properties.getRunnerImage())) {
            throw new IllegalStateException("pyclaw.sandbox.runner-image is required when sandbox orchestration is enabled");
        }
        ensureUserNamespace(userId, username);
        String namespace = namespaceForUser(userId);
        String appName = resourceName("sandbox-runner", clawId);
        String pvcName = resourceName("workspace", clawId);
        Map<String, String> labels = baseClawLabels(userId, clawId);

        client().persistentVolumeClaims().inNamespace(namespace).resource(new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(pvcName)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withAccessModes("ReadWriteOnce")
                .withStorageClassName(blank(properties.getPvcStorageClassName()) ? null : properties.getPvcStorageClassName())
                .withNewResources()
                .addToRequests("storage", new Quantity(properties.getPvcStorageSize()))
                .endResources()
                .endSpec()
                .build()).serverSideApply();

        var resources = new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(properties.getCpuRequest()))
                .addToRequests("memory", new Quantity(properties.getMemoryRequest()))
                .addToLimits("cpu", new Quantity(properties.getCpuLimit()))
                .addToLimits("memory", new Quantity(properties.getMemoryLimit()))
                .build();
        var container = new ContainerBuilder()
                .withName("sandbox-runner")
                .withImage(properties.getRunnerImage())
                .withImagePullPolicy(properties.getRunnerImagePullPolicy())
                .addNewPort().withName("http").withContainerPort(properties.getRunnerPort()).endPort()
                .addNewEnv().withName("PYCLAW_CLAW_ID").withValue(clawId).endEnv()
                .addNewEnv().withName("PYCLAW_OWNER_USER_ID").withValue(userId).endEnv()
                .addNewEnv().withName("PYCLAW_CLAW_NAME").withValue(clawName == null ? "" : clawName).endEnv()
                .addNewEnvFrom().withNewSecretRef(clawSecretName(clawId), true).endEnvFrom()
                .addNewVolumeMount().withName("workspace").withMountPath(properties.getWorkspaceMountPath()).endVolumeMount()
                .withResources(resources)
                .withSecurityContext(new SecurityContextBuilder()
                        .withAllowPrivilegeEscalation(false)
                        .withReadOnlyRootFilesystem(false)
                        .withNewCapabilities().addToDrop("ALL").endCapabilities()
                        .build())
                .build();

        List<LocalObjectReference> imagePullSecrets = blank(properties.getImagePullSecretName())
                ? List.of()
                : List.of(new LocalObjectReferenceBuilder().withName(properties.getImagePullSecretName()).build());

        client().apps().deployments().inNamespace(namespace).resource(new DeploymentBuilder()
                .withNewMetadata()
                .withName(appName)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector().withMatchLabels(selectorLabels(clawId)).endSelector()
                .withNewTemplate()
                .withNewMetadata().withLabels(labels).endMetadata()
                .withNewSpec()
                .withServiceAccountName("sandbox-runner")
                .withAutomountServiceAccountToken(false)
                .withSecurityContext(new PodSecurityContextBuilder()
                        .withRunAsNonRoot(true)
                        .withRunAsUser(10001L)
                        .withRunAsGroup(10001L)
                        .withFsGroup(10001L)
                        .withSeccompProfile(new SeccompProfileBuilder()
                                .withType("RuntimeDefault")
                                .build())
                        .build())
                .withImagePullSecrets(imagePullSecrets)
                .withContainers(container)
                .addNewVolume().withName("workspace").withNewPersistentVolumeClaim().withClaimName(pvcName).endPersistentVolumeClaim().endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build()).serverSideApply();

        client().services().inNamespace(namespace).resource(new ServiceBuilder()
                .withNewMetadata()
                .withName(appName)
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(selectorLabels(clawId))
                .addNewPort().withName("http").withPort(properties.getRunnerPort()).withNewTargetPort("http").endPort()
                .endSpec()
                .build()).serverSideApply();
        log.info("ensured claw sandbox: namespace={} claw_id={} deployment={}", namespace, clawId, appName);
    }

    // ---- Secret naming helpers ----

    public static String clawSecretName(String clawId) {
        return "claw-secret-" + clawId;
    }

    public static String userSecretName(String userId) {
        return "user-secret-" + userId;
    }

    // ---- K8s Secret sync ----

    public void ensureClawSecret(String userId, String clawId, Map<String, String> values) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        String secretName = clawSecretName(clawId);
        Map<String, String> labels = baseClawLabels(userId, clawId);
        createOrUpdateSecret(namespace, secretName, labels, values);
        log.info("ensured claw secret: namespace={} name={}", namespace, secretName);
    }

    public void ensureUserSecret(String userId, Map<String, String> values) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        String secretName = userSecretName(userId);
        Map<String, String> labels = baseUserLabels(userId);
        createOrUpdateSecret(namespace, secretName, labels, values);
        log.info("ensured user secret: namespace={} name={}", namespace, secretName);
    }

    public void deleteClawSecret(String userId, String clawId) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        String secretName = clawSecretName(clawId);
        client().secrets().inNamespace(namespace).withName(secretName).delete();
        log.info("deleted claw secret: namespace={} name={}", namespace, secretName);
    }

    public void deleteClawSecretByName(String userId, String secretName) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        client().secrets().inNamespace(namespace).withName(secretName).delete();
        log.info("deleted secret: namespace={} name={}", namespace, secretName);
    }

    private void createOrUpdateSecret(String namespace, String name, Map<String, String> labels, Map<String, String> stringData) {
        Map<String, String> encodedData = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : stringData.entrySet()) {
            encodedData.put(entry.getKey(), java.util.Base64.getEncoder().encodeToString(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        client().secrets().inNamespace(namespace).resource(new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(labels)
                .endMetadata()
                .withType("Opaque")
                .withData(encodedData)
                .build()).serverSideApply();
    }

    // ---- Lifecycle: scale runner replicas ----

    public void scaleClawDeployment(String userId, String clawId, int replicas) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        String appName = resourceName("sandbox-runner", clawId);
        client().apps().deployments().inNamespace(namespace).withName(appName).scale(replicas);
        log.info("scaled claw deployment: namespace={} deployment={} replicas={}", namespace, appName, replicas);
    }

    public void scaleUserDeployments(String userId, int replicas) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        Map<String, String> labels = Map.of(COMPONENT_LABEL, "sandbox-runner");
        client().apps().deployments().inNamespace(namespace).withLabels(labels).list().getItems()
                .forEach(deployment -> {
                    client().apps().deployments().inNamespace(namespace)
                            .withName(deployment.getMetadata().getName()).scale(replicas);
                });
        log.info("scaled all user deployments: namespace={} replicas={}", namespace, replicas);
    }

    public void deleteClawSandbox(String userId, String clawId) {
        if (!properties.isEnabled()) {
            return;
        }
        String namespace = namespaceForUser(userId);
        String appName = resourceName("sandbox-runner", clawId);
        String pvcName = resourceName("workspace", clawId);
        client().services().inNamespace(namespace).withName(appName).delete();
        client().apps().deployments().inNamespace(namespace).withName(appName).delete();
        if (properties.isDeletePvcOnClawDelete()) {
            client().persistentVolumeClaims().inNamespace(namespace).withName(pvcName).delete();
            log.info("deleted claw sandbox with pvc: namespace={} claw_id={}", namespace, clawId);
        } else {
            log.info("deleted claw sandbox, pvc preserved: namespace={} claw_id={} pvc={}", namespace, clawId, pvcName);
        }
    }

    // ---- ResourceQuota ----

    private void ensureResourceQuota(String namespace) {
        if (!properties.isResourceQuotaEnabled()) {
            return;
        }
        client().resourceQuotas().inNamespace(namespace).resource(new io.fabric8.kubernetes.api.model.ResourceQuotaBuilder()
                .withNewMetadata()
                .withName("pyclaw-user-quota")
                .endMetadata()
                .withNewSpec()
                .addToHard("requests.cpu", new Quantity(properties.getNamespaceCpuRequestQuota()))
                .addToHard("limits.cpu", new Quantity(properties.getNamespaceCpuLimitQuota()))
                .addToHard("requests.memory", new Quantity(properties.getNamespaceMemoryRequestQuota()))
                .addToHard("limits.memory", new Quantity(properties.getNamespaceMemoryLimitQuota()))
                .addToHard("persistentvolumeclaims", new Quantity(properties.getNamespacePvcQuota()))
                .addToHard("requests.storage", new Quantity(properties.getNamespaceStorageQuota()))
                .endSpec()
                .build()).serverSideApply();
        log.info("ensured resource quota: namespace={}", namespace);
    }

    // ---- LimitRange ----

    private void ensureLimitRange(String namespace) {
        if (!properties.isLimitRangeEnabled()) {
            return;
        }
        client().limitRanges().inNamespace(namespace).resource(new io.fabric8.kubernetes.api.model.LimitRangeBuilder()
                .withNewMetadata()
                .withName("pyclaw-default-limits")
                .endMetadata()
                .withNewSpec()
                .addNewLimit()
                .withType("Container")
                .addToDefaultRequest("cpu", new Quantity(properties.getDefaultCpuRequest()))
                .addToDefaultRequest("memory", new Quantity(properties.getDefaultMemoryRequest()))
                .addToDefault("cpu", new Quantity(properties.getDefaultCpuLimit()))
                .addToDefault("memory", new Quantity(properties.getDefaultMemoryLimit()))
                .endLimit()
                .endSpec()
                .build()).serverSideApply();
        log.info("ensured limit range: namespace={}", namespace);
    }

    // ---- NetworkPolicy ----

    private void ensureNetworkPolicies(String namespace) {
        if (!properties.isNetworkPolicyEnabled()) {
            return;
        }
        // Policy 1: deny all ingress by default
        client().network().v1().networkPolicies().inNamespace(namespace).resource(new NetworkPolicyBuilder()
                .withNewMetadata()
                .withName("deny-ingress-by-default")
                .endMetadata()
                .withNewSpec()
                .withPodSelector(new io.fabric8.kubernetes.api.model.LabelSelectorBuilder().build())
                .withPolicyTypes("Ingress")
                .endSpec()
                .build()).serverSideApply();

        // Policy 2: allow control plane (pyclaw namespace) to access sandbox-runners on port 8000
        client().network().v1().networkPolicies().inNamespace(namespace).resource(new NetworkPolicyBuilder()
                .withNewMetadata()
                .withName("allow-control-plane-to-runner")
                .endMetadata()
                .withNewSpec()
                .withPodSelector(new io.fabric8.kubernetes.api.model.LabelSelectorBuilder()
                        .addToMatchLabels(COMPONENT_LABEL, "sandbox-runner")
                        .build())
                .withPolicyTypes("Ingress")
                .withIngress(new NetworkPolicyIngressRuleBuilder()
                        .withFrom(new NetworkPolicyPeerBuilder()
                                .withNewNamespaceSelector()
                                .addToMatchLabels("kubernetes.io/metadata.name", "pyclaw")
                                .endNamespaceSelector()
                                .build())
                        .withPorts(new io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder()
                                .withProtocol("TCP")
                                .withPort(new IntOrString(properties.getRunnerPort()))
                                .build())
                        .build())
                .endSpec()
                .build()).serverSideApply();

        log.info("ensured network policies: namespace={}", namespace);
    }

    private void ensureSandboxServiceAccount(String namespace) {
        client().serviceAccounts().inNamespace(namespace).resource(new io.fabric8.kubernetes.api.model.ServiceAccountBuilder()
                .withNewMetadata()
                .withName("sandbox-runner")
                .endMetadata()
                .withAutomountServiceAccountToken(false)
                .build()).serverSideApply();
        log.info("ensured sandbox service account: namespace={}", namespace);
    }

    private void ensureImagePullSecret(String targetNamespace) {
        String secretName = properties.getImagePullSecretName();
        if (blank(secretName)) {
            return;
        }
        String sourceNamespace = imagePullSecretSourceNamespace();
        Secret source = client().secrets().inNamespace(sourceNamespace).withName(secretName).get();
        if (source == null) {
            throw new IllegalStateException("sandbox image pull secret not found: namespace=" + sourceNamespace + " name=" + secretName);
        }
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PART_OF_LABEL, "pyclaw");
        labels.put(COMPONENT_LABEL, "sandbox-image-pull-secret");
        client().secrets().inNamespace(targetNamespace).resource(new SecretBuilder()
                .withNewMetadata()
                .withName(secretName)
                .withLabels(labels)
                .endMetadata()
                .withType(source.getType())
                .withData(source.getData())
                .build()).serverSideApply();
        log.info("ensured sandbox image pull secret: source_namespace={} target_namespace={} name={}", sourceNamespace, targetNamespace, secretName);
    }

    private String imagePullSecretSourceNamespace() {
        String sourceNamespace = properties.getImagePullSecretSourceNamespace();
        if (blank(sourceNamespace)) {
            sourceNamespace = System.getenv("POD_NAMESPACE");
        }
        if (blank(sourceNamespace)) {
            throw new IllegalStateException("pyclaw.sandbox.image-pull-secret-source-namespace is required when sandbox image pull secret is configured");
        }
        return sourceNamespace;
    }

    private KubernetesClient client() {
        return clientProvider.getIfAvailable(() -> {
            throw new IllegalStateException("Kubernetes client is unavailable; enable pyclaw.sandbox.enabled only in a Kubernetes runtime");
        });
    }

    private Map<String, String> baseUserLabels(String userId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PART_OF_LABEL, "pyclaw");
        labels.put(properties.getNamespaceLabelKey(), dnsLabel(userId));
        labels.put(USER_LABEL, dnsLabel(userId));
        return labels;
    }

    private Map<String, String> baseClawLabels(String userId, String clawId) {
        Map<String, String> labels = baseUserLabels(userId);
        labels.put(APP_LABEL, resourceName("sandbox-runner", clawId));
        labels.put(COMPONENT_LABEL, "sandbox-runner");
        labels.put(CLAW_LABEL, dnsLabel(clawId));
        return labels;
    }

    private Map<String, String> selectorLabels(String clawId) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(APP_LABEL, resourceName("sandbox-runner", clawId));
        labels.put(COMPONENT_LABEL, "sandbox-runner");
        labels.put(CLAW_LABEL, dnsLabel(clawId));
        return labels;
    }

    private String resourceName(String prefix, String id) {
        return dnsName(prefix + "-" + id);
    }

    private String dnsLabel(String value) {
        return dnsName(value == null ? "unknown" : value);
    }

    private String dnsName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (normalized.isBlank()) {
            normalized = "x";
        }
        if (normalized.length() > 63) {
            normalized = normalized.substring(0, 63).replaceAll("-$", "");
        }
        return normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
