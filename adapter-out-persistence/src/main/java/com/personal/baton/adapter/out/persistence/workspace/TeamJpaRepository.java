package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Team;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface TeamJpaRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByIdempotencyKeyHash(String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<Team> findWithSharedLockById(UUID teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Team> findForUpdateById(UUID teamId);
}
