package com.clawsaas.agentmarketplace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "agent_package_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_agent_package_versions_pkg_ver", columnNames = {"packageId", "version"}),
        indexes = {
                @Index(name = "idx_agent_package_versions_pkg_status_created", columnList = "packageId,status,createdAt"),
                @Index(name = "idx_agent_package_versions_status_created", columnList = "status,createdAt")
        }
)
public class AgentPackageVersionEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String packageId;
    @Column(nullable = false)
    private String version;
    @Column(nullable = false)
    private String status;
    @Lob
    private String manifestJson;
    @Lob
    private String systemPromptSnapshot;
    @Lob
    private String personaFilesJson;
    @Lob
    private String skillFilesJson;
    @Column(nullable = false)
    private String defaultProfile;
    private String requiredProfile;
    @Lob
    private String capabilitiesJson;
    @Lob
    private String inputContractJson;
    @Lob
    private String outputContractJson;
    @Lob
    private String changelog;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPackageId() { return packageId; }
    public void setPackageId(String packageId) { this.packageId = packageId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
    public String getSystemPromptSnapshot() { return systemPromptSnapshot; }
    public void setSystemPromptSnapshot(String systemPromptSnapshot) { this.systemPromptSnapshot = systemPromptSnapshot; }
    public String getPersonaFilesJson() { return personaFilesJson; }
    public void setPersonaFilesJson(String personaFilesJson) { this.personaFilesJson = personaFilesJson; }
    public String getSkillFilesJson() { return skillFilesJson; }
    public void setSkillFilesJson(String skillFilesJson) { this.skillFilesJson = skillFilesJson; }
    public String getDefaultProfile() { return defaultProfile; }
    public void setDefaultProfile(String defaultProfile) { this.defaultProfile = defaultProfile; }
    public String getRequiredProfile() { return requiredProfile; }
    public void setRequiredProfile(String requiredProfile) { this.requiredProfile = requiredProfile; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public String getInputContractJson() { return inputContractJson; }
    public void setInputContractJson(String inputContractJson) { this.inputContractJson = inputContractJson; }
    public String getOutputContractJson() { return outputContractJson; }
    public void setOutputContractJson(String outputContractJson) { this.outputContractJson = outputContractJson; }
    public String getChangelog() { return changelog; }
    public void setChangelog(String changelog) { this.changelog = changelog; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
