package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentCreationIdempotencyJpaRepository
        extends JpaRepository<ContentCreationIdempotency, UUID> {

    Optional<ContentCreationIdempotency> findByTeamIdAndIdempotencyHash(
            UUID teamId,
            String idempotencyHash
    );
}
