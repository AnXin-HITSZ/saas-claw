package com.clawsaas.claw.repository;

import com.clawsaas.claw.domain.ClawEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClawRepository extends JpaRepository<ClawEntity, String> {
    List<ClawEntity> findByOwnerUserIdOrderByUpdatedAtDesc(String ownerUserId);

    @Query("""
            select count(c) > 0 from ClawEntity c
            where c.feishuEnabled = true
              and c.feishuPeerKind = :peerKind
              and c.feishuPeerId = :peerId
              and ((:accountId is null and c.feishuAccountId is null) or c.feishuAccountId = :accountId)
              and (:excludeId is null or c.id <> :excludeId)
            """)
    boolean existsFeishuBinding(
            @Param("accountId") String accountId,
            @Param("peerKind") String peerKind,
            @Param("peerId") String peerId,
            @Param("excludeId") String excludeId
    );
}
