package com.personal.baton.application.workspace.port.out;

import com.personal.baton.domain.workspace.Season;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceSeasonRepository {

    Season saveSeason(Season season);

    Optional<Season> findSeasonById(UUID seasonId);

    Optional<Season> findSeasonByTeamIdAndIdWithSharedLock(UUID teamId, UUID seasonId);

    Optional<Season> findSeasonByTeamIdAndIdForUpdate(UUID teamId, UUID seasonId);

    List<Season> findSeasonsByTeamId(UUID teamId);

    List<ScheduledSeasonCandidate> findScheduledSeasonCandidates();

    Optional<Season> findActiveSeasonByTeamId(UUID teamId);

    boolean existsSeasonByPreviousSeasonId(UUID previousSeasonId);

    record ScheduledSeasonCandidate(UUID teamId, UUID seasonId) {
    }
}
