package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Team;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamJpaRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByIdempotencyKeyHash(String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select team from Team team where team.id = :teamId")
    Optional<Team> findByIdWithSharedLock(@Param("teamId") UUID teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from Team team where team.id = :teamId")
    Optional<Team> findByIdForUpdate(@Param("teamId") UUID teamId);
}
