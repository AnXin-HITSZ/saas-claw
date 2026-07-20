package com.clawsaas.gateway.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Minimal stub entity for Claw.
 * Full entity lives in runtime-service.
 */
@Entity
@Table(name = "claws")
public class ClawEntity {
    @Id
    private String id;
    @Column(nullable = false)
    private String ownerUserId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
}
