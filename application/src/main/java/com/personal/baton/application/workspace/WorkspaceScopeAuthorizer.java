package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import com.personal.baton.domain.workspace.TeamPermission;
import java.util.UUID;

@Component
final class WorkspaceScopeAuthorizer {

    private final WorkspaceAccessRepository accessRepository;
    private final WorkspaceSeasonRepository seasonRepository;
    private final WorkspaceAccessControl accessControl;
    private final TeamAccountAccessPolicy accountAccess;

    WorkspaceScopeAuthorizer(
            WorkspaceAccessRepository accessRepository,
            WorkspaceSeasonRepository seasonRepository,
            WorkspaceAccessControl accessControl,
            TeamAccountAccessPolicy accountAccess
    ) {
        this.accessRepository = accessRepository;
        this.seasonRepository = seasonRepository;
        this.accessControl = accessControl;
        this.accountAccess = accountAccess;
    }

    WorkspaceScope authorizeRead(UUID teamId, UUID seasonId, String accessKey) {
        WorkspaceScope scope = requireScope(teamId, seasonId);
        if (scope.team().isAccountAccessEnabled()) {
            TeamPermission permission = accountAccess.requireMembership(scope.team()).getPermission();
            return new WorkspaceScope(scope.team(), scope.season(), permission);
        }
        accessControl.verifyAccessKey(scope.team(), accessKey);
        return scope;
    }

    Team authorizeTeamRead(UUID teamId, String accessKey) {
        Team team = accessRepository.findTeamById(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        if (team.isAccountAccessEnabled()) accountAccess.requireRead(team);
        else accessControl.verifyAccessKey(team, accessKey);
        return team;
    }

    WorkspaceScope authorizeMutation(UUID teamId, UUID seasonId, String accessKey) {
        Team team = accessRepository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = seasonRepository.findSeasonByTeamIdAndIdWithSharedLock(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        verifyWrite(team, accessKey);
        requireOpenSeason(season);
        return new WorkspaceScope(team, season);
    }

    WorkspaceScope authorizeSeasonForUpdate(
            UUID teamId,
            UUID seasonId,
            String accessKey
    ) {
        WorkspaceScope scope = requireSeasonForUpdate(teamId, seasonId);
        verifyWrite(scope.team(), accessKey);
        requireOpenSeason(scope.season());
        return scope;
    }

    WorkspaceScope requireSeasonForUpdate(UUID teamId, UUID seasonId) {
        Team team = accessRepository.findTeamByIdWithSharedLock(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = seasonRepository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        return new WorkspaceScope(team, season);
    }

    WorkspaceScope authorizeSeasonLifecycle(
            UUID teamId,
            UUID seasonId,
            String accessKey
    ) {
        Team team = accessRepository.findTeamByIdForUpdate(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        Season season = seasonRepository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId)
                .orElseThrow(() -> notFound("SEASON_NOT_FOUND", "시즌을 찾을 수 없습니다"));
        if (team.isAccountAccessEnabled()) {
            accountAccess.requireAdministrator(team);
        } else {
            accessControl.verifyAccessKey(team, accessKey);
        }
        return new WorkspaceScope(team, season);
    }

    WorkspaceScope authorizeAdministratorMutation(UUID teamId, UUID seasonId, String accessKey) {
        WorkspaceScope scope = authorizeSeasonLifecycle(teamId, seasonId, accessKey);
        requireOpenSeason(scope.season());
        return scope;
    }

    void requireMemberDeactivation(Team team, UUID memberId) { accountAccess.requireOtherAdministrator(team, memberId); }
    void requireConfirmedMember(Team team, UUID memberId) { accountAccess.requireConfirmedMember(team, memberId); }

    private void verifyWrite(Team team, String accessKey) {
        if (team.isAccountAccessEnabled()) accountAccess.requireWrite(team);
        else accessControl.verifyAccessKey(team, accessKey);
    }

    WorkspaceScope requireScope(UUID teamId, UUID seasonId) {
        Team team = accessRepository.findTeamById(teamId)
                .orElseThrow(() -> notFound("TEAM_NOT_FOUND", "팀을 찾을 수 없습니다"));
        return new WorkspaceScope(team, requireSeason(teamId, seasonId));
    }

    private Season requireSeason(UUID teamId, UUID seasonId) {
        return seasonRepository.findSeasonById(seasonId)
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

record WorkspaceScope(Team team, Season season, TeamPermission permission) {
    WorkspaceScope(Team team, Season season) { this(team, season, null); }
}
