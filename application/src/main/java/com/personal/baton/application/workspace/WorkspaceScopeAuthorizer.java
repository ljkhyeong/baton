package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import java.util.UUID;

final class WorkspaceScopeAuthorizer {

    private final WorkspaceRepository repository;
    private final WorkspaceAccessControl accessControl;

    WorkspaceScopeAuthorizer(
            WorkspaceRepository repository,
            WorkspaceAccessControl accessControl
    ) {
        this.repository = repository;
        this.accessControl = accessControl;
    }

    WorkspaceScope authorizeRead(UUID teamId, UUID seasonId, String accessKey) {
        WorkspaceScope scope = requireScope(teamId, seasonId);
        accessControl.verifyAccessKey(scope.team(), accessKey);
        return scope;
    }

    Team authorizeTeamRead(UUID teamId, String accessKey) {
        Team team = repository.findTeamById(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        accessControl.verifyAccessKey(team, accessKey);
        return team;
    }

    WorkspaceScope authorizeMutation(UUID teamId, UUID seasonId, String accessKey) {
        Team team = repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdWithSharedLock(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        accessControl.verifyAccessKey(team, accessKey);
        requireOpenSeason(season);
        return new WorkspaceScope(team, season);
    }

    WorkspaceScope authorizeSeasonForUpdate(
            UUID teamId,
            UUID seasonId,
            String accessKey
    ) {
        WorkspaceScope scope = requireSeasonForUpdate(teamId, seasonId);
        accessControl.verifyAccessKey(scope.team(), accessKey);
        requireOpenSeason(scope.season());
        return scope;
    }

    WorkspaceScope requireSeasonForUpdate(UUID teamId, UUID seasonId) {
        Team team = repository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        return new WorkspaceScope(team, season);
    }

    WorkspaceScope authorizeSeasonLifecycle(
            UUID teamId,
            UUID seasonId,
            String accessKey
    ) {
        Team team = repository.findTeamByIdForUpdate(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        accessControl.verifyAccessKey(team, accessKey);
        return new WorkspaceScope(team, season);
    }

    WorkspaceScope requireScope(UUID teamId, UUID seasonId) {
        Team team = repository.findTeamById(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        return new WorkspaceScope(team, requireSeason(teamId, seasonId));
    }

    private Season requireSeason(UUID teamId, UUID seasonId) {
        return repository.findSeasonById(seasonId)
                .filter(found -> found.getTeamId().equals(teamId))
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
    }

    private void requireOpenSeason(Season season) {
        if (season.isEnded()) {
            throw new SeasonEndedException();
        }
    }

    private WorkspaceNotFoundException notFound(String code, String message) {
        return new WorkspaceNotFoundException(code, message);
    }
}

record WorkspaceScope(Team team, Season season) {
}
