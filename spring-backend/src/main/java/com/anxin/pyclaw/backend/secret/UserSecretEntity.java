package com.anxin.pyclaw.backend.secret;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "user_secrets")
public class UserSecretEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String ownerUserId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String scope;
    private String clawId;
    private String kubernetesSecretName;
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String encryptedValuesJson;
    @Lob
    private String maskedValuesJson;
    @Column(nullable = false)
    private boolean enabled;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getClawId() { return clawId; }
    public void setClawId(String clawId) { this.clawId = clawId; }
    public String getKubernetesSecretName() { return kubernetesSecretName; }
    public void setKubernetesSecretName(String kubernetesSecretName) { this.kubernetesSecretName = kubernetesSecretName; }
    public String getEncryptedValuesJson() { return encryptedValuesJson; }
    public void setEncryptedValuesJson(String encryptedValuesJson) { this.encryptedValuesJson = encryptedValuesJson; }
    public String getMaskedValuesJson() { return maskedValuesJson; }
    public void setMaskedValuesJson(String maskedValuesJson) { this.maskedValuesJson = maskedValuesJson; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
