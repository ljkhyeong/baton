package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessKeyChangeHistoryJpaRepository extends JpaRepository<AccessKeyChangeHistory, UUID> {

    boolean existsByTeamIdAndIdempotencyHash(UUID teamId, String idempotencyHash);
}
