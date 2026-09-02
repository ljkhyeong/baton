package com.personal.baton.application.workspace;

import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.application.workspace.error.RoleHandoffStateConflictException;
import com.personal.baton.application.workspace.error.SeasonSuccessorExistsException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.SeasonResult;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class WorkspaceSeasonEndingCoordinator {

    private final WorkspaceSeasonRepository seasonRepository;
    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceRecordsRepository recordsRepository;
    private final Clock clock;
    private final WorkspaceResultMapper resultMapper;
    private final WatchMonitorChangeRecorder watchMonitorChangeRecorder;

    WorkspaceSeasonEndingCoordinator(
            WorkspaceSeasonRepository seasonRepository,
            WorkspacePeopleRepository peopleRepository,
            WorkspaceRecordsRepository recordsRepository,
            Clock clock,
            WorkspaceResultMapper resultMapper,
            WatchMonitorChangeRecorder watchMonitorChangeRecorder
    ) {
        this.seasonRepository = seasonRepository;
        this.peopleRepository = peopleRepository;
        this.recordsRepository = recordsRepository;
        this.clock = clock;
        this.resultMapper = resultMapper;
        this.watchMonitorChangeRecorder = watchMonitorChangeRecorder;
    }

    SeasonResult update(UUID teamId, Season season, boolean ended) {
        UUID seasonId = season.getId();
        boolean endingChanged = season.isEnded() != ended;
        if (ended && peopleRepository.existsOpenRoleHandoffBySeasonId(seasonId)) {
            throw new RoleHandoffStateConflictException(
                    "준비 중이거나 수락을 기다리는 바통을 수락 또는 취소한 뒤 시즌을 종료해 주세요"
            );
        }
        if (!ended) {
            if (seasonRepository.existsSeasonByPreviousSeasonId(seasonId)) {
                throw new SeasonSuccessorExistsException();
            }
            seasonRepository.findActiveSeasonByTeamId(teamId)
                    .filter(active -> !active.getId().equals(seasonId))
                    .ifPresent(active -> {
                        throw new WorkspaceContentConflictException();
                    });
        }

        season.updateEnding(ended, Instant.now(clock));
        Season savedSeason = seasonRepository.saveSeason(season);
        if (endingChanged) {
            watchMonitorChangeRecorder.recordSeasonState(
                    findSeasonResources(teamId, seasonId),
                    ended
            );
        }
        return resultMapper.toSeasonResult(savedSeason);
    }

    private List<RoleResource> findSeasonResources(UUID teamId, UUID seasonId) {
        List<UUID> roleIds = peopleRepository.findRolesByTeamIdAndSeasonId(teamId, seasonId).stream()
                .map(Role::getId)
                .toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return recordsRepository.findRoleResourcesByRoleIds(roleIds);
    }
}
