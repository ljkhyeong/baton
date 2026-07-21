package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.domain.workspace.Team;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamJpaRepository extends JpaRepository<Team, UUID> {

    Optional<Team> findByIdempotencyKeyHash(String idempotencyKeyHash);
}
