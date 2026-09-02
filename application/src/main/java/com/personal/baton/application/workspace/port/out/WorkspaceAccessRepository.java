package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.AccessKeyChangeHistory;
import com.personal.baton.domain.workspace.ContentCreationIdempotency;
import com.personal.baton.domain.workspace.Team;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WorkspaceAccessRepository {

    Team saveTeam(Team team);

    AccessKeyChangeHistory saveAccessKeyChangeHistory(AccessKeyChangeHistory history);

    ContentCreationIdempotency saveContentCreationIdempotency(ContentCreationIdempotency idempotency);

    Optional<Team> findTeamById(UUID teamId);

    Optional<Team> findTeamByIdWithSharedLock(UUID teamId);

    Optional<Team> findTeamByIdForUpdate(UUID teamId);

    Optional<Team> findTeamByIdempotencyKeyHash(String idempotencyKeyHash);

    Optional<ContentCreationIdempotency> findContentCreationIdempotency(
            UUID teamId,
            String idempotencyHash
    );

    boolean existsAccessKeyChangeHistory(UUID teamId, String idempotencyHash);

    Set<String> findAccessKeyChangeIdempotencyHashes(
            UUID teamId,
            List<String> idempotencyHashes
    );
}
