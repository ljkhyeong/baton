package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccessKeyChangeHistoryJpaRepository extends JpaRepository<AccessKeyChangeHistory, UUID> {

    boolean existsByTeamIdAndIdempotencyHash(UUID teamId, String idempotencyHash);

    @Query("""
            select history.idempotencyHash
            from AccessKeyChangeHistory history
            where history.teamId = :teamId
              and history.idempotencyHash in :idempotencyHashes
            """)
    Set<String> findIdempotencyHashes(
            @Param("teamId") UUID teamId,
            @Param("idempotencyHashes") List<String> idempotencyHashes
    );
}
