package com.anxin.pyclaw.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pyclaw.sandbox")
public class PyclawSandboxProperties {
    private boolean enabled;
    private String namespacePrefix = "pyclaw-user";
    private String namespaceLabelKey = "pyclaw.io/owner-user-id";
    private String runnerImage;
    private String runnerImagePullPolicy = "IfNotPresent";
    private int runnerPort = 8000;
    private String workspaceMountPath = "/workspace";
    private String pvcStorageSize = "1Gi";
    private String pvcStorageClassName;
    private String cpuRequest = "100m";
    private String memoryRequest = "256Mi";
    private String cpuLimit = "500m";
    private String memoryLimit = "768Mi";
    private String serviceAccountName = "default";
    private String imagePullSecretName;
    private String imagePullSecretSourceNamespace;
    private boolean networkPolicyEnabled;
    private boolean resourceQuotaEnabled;
    private boolean limitRangeEnabled;
    private String namespaceCpuRequestQuota = "1000m";
    private String namespaceCpuLimitQuota = "2000m";
    private String namespaceMemoryRequestQuota = "2Gi";
    private String namespaceMemoryLimitQuota = "4Gi";
    private String namespacePvcQuota = "5";
    private String namespaceStorageQuota = "5Gi";
    private String defaultCpuRequest = "50m";
    private String defaultMemoryRequest = "128Mi";
    private String defaultCpuLimit = "500m";
    private String defaultMemoryLimit = "512Mi";
    private boolean deletePvcOnClawDelete = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getNamespacePrefix() { return namespacePrefix; }
    public void setNamespacePrefix(String namespacePrefix) { this.namespacePrefix = namespacePrefix; }
    public String getNamespaceLabelKey() { return namespaceLabelKey; }
    public void setNamespaceLabelKey(String namespaceLabelKey) { this.namespaceLabelKey = namespaceLabelKey; }
    public String getRunnerImage() { return runnerImage; }
    public void setRunnerImage(String runnerImage) { this.runnerImage = runnerImage; }
    public String getRunnerImagePullPolicy() { return runnerImagePullPolicy; }
    public void setRunnerImagePullPolicy(String runnerImagePullPolicy) { this.runnerImagePullPolicy = runnerImagePullPolicy; }
    public int getRunnerPort() { return runnerPort; }
    public void setRunnerPort(int runnerPort) { this.runnerPort = runnerPort; }
    public String getWorkspaceMountPath() { return workspaceMountPath; }
    public void setWorkspaceMountPath(String workspaceMountPath) { this.workspaceMountPath = workspaceMountPath; }
    public String getPvcStorageSize() { return pvcStorageSize; }
    public void setPvcStorageSize(String pvcStorageSize) { this.pvcStorageSize = pvcStorageSize; }
    public String getPvcStorageClassName() { return pvcStorageClassName; }
    public void setPvcStorageClassName(String pvcStorageClassName) { this.pvcStorageClassName = pvcStorageClassName; }
    public String getCpuRequest() { return cpuRequest; }
    public void setCpuRequest(String cpuRequest) { this.cpuRequest = cpuRequest; }
    public String getMemoryRequest() { return memoryRequest; }
    public void setMemoryRequest(String memoryRequest) { this.memoryRequest = memoryRequest; }
    public String getCpuLimit() { return cpuLimit; }
    public void setCpuLimit(String cpuLimit) { this.cpuLimit = cpuLimit; }
    public String getMemoryLimit() { return memoryLimit; }
    public void setMemoryLimit(String memoryLimit) { this.memoryLimit = memoryLimit; }
    public String getServiceAccountName() { return serviceAccountName; }
    public void setServiceAccountName(String serviceAccountName) { this.serviceAccountName = serviceAccountName; }
    public String getImagePullSecretName() { return imagePullSecretName; }
    public void setImagePullSecretName(String imagePullSecretName) { this.imagePullSecretName = imagePullSecretName; }
    public String getImagePullSecretSourceNamespace() { return imagePullSecretSourceNamespace; }
    public void setImagePullSecretSourceNamespace(String imagePullSecretSourceNamespace) { this.imagePullSecretSourceNamespace = imagePullSecretSourceNamespace; }
    public boolean isNetworkPolicyEnabled() { return networkPolicyEnabled; }
    public void setNetworkPolicyEnabled(boolean networkPolicyEnabled) { this.networkPolicyEnabled = networkPolicyEnabled; }
    public boolean isResourceQuotaEnabled() { return resourceQuotaEnabled; }
    public void setResourceQuotaEnabled(boolean resourceQuotaEnabled) { this.resourceQuotaEnabled = resourceQuotaEnabled; }
    public boolean isLimitRangeEnabled() { return limitRangeEnabled; }
    public void setLimitRangeEnabled(boolean limitRangeEnabled) { this.limitRangeEnabled = limitRangeEnabled; }
    public String getNamespaceCpuRequestQuota() { return namespaceCpuRequestQuota; }
    public void setNamespaceCpuRequestQuota(String namespaceCpuRequestQuota) { this.namespaceCpuRequestQuota = namespaceCpuRequestQuota; }
    public String getNamespaceCpuLimitQuota() { return namespaceCpuLimitQuota; }
    public void setNamespaceCpuLimitQuota(String namespaceCpuLimitQuota) { this.namespaceCpuLimitQuota = namespaceCpuLimitQuota; }
    public String getNamespaceMemoryRequestQuota() { return namespaceMemoryRequestQuota; }
    public void setNamespaceMemoryRequestQuota(String namespaceMemoryRequestQuota) { this.namespaceMemoryRequestQuota = namespaceMemoryRequestQuota; }
    public String getNamespaceMemoryLimitQuota() { return namespaceMemoryLimitQuota; }
    public void setNamespaceMemoryLimitQuota(String namespaceMemoryLimitQuota) { this.namespaceMemoryLimitQuota = namespaceMemoryLimitQuota; }
    public String getNamespacePvcQuota() { return namespacePvcQuota; }
    public void setNamespacePvcQuota(String namespacePvcQuota) { this.namespacePvcQuota = namespacePvcQuota; }
    public String getNamespaceStorageQuota() { return namespaceStorageQuota; }
    public void setNamespaceStorageQuota(String namespaceStorageQuota) { this.namespaceStorageQuota = namespaceStorageQuota; }
    public String getDefaultCpuRequest() { return defaultCpuRequest; }
    public void setDefaultCpuRequest(String defaultCpuRequest) { this.defaultCpuRequest = defaultCpuRequest; }
    public String getDefaultMemoryRequest() { return defaultMemoryRequest; }
    public void setDefaultMemoryRequest(String defaultMemoryRequest) { this.defaultMemoryRequest = defaultMemoryRequest; }
    public String getDefaultCpuLimit() { return defaultCpuLimit; }
    public void setDefaultCpuLimit(String defaultCpuLimit) { this.defaultCpuLimit = defaultCpuLimit; }
    public String getDefaultMemoryLimit() { return defaultMemoryLimit; }
    public void setDefaultMemoryLimit(String defaultMemoryLimit) { this.defaultMemoryLimit = defaultMemoryLimit; }
    public boolean isDeletePvcOnClawDelete() { return deletePvcOnClawDelete; }
    public void setDeletePvcOnClawDelete(boolean deletePvcOnClawDelete) { this.deletePvcOnClawDelete = deletePvcOnClawDelete; }
}
