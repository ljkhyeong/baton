package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("policy")
class WorkspaceScopeAuthorizerTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACCESS_KEY = "workspace-access-key";

    @DisplayName("일반 변경 권한은 팀과 시즌의 공유 잠금을 순서대로 획득한다")
    @Test
    void authorizesMutationWithSharedTeamAndSeasonLocks() {
        AuthorizationFixture fixture = fixture();
        when(fixture.repository.findTeamByIdWithSharedLock(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.repository.findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        WorkspaceScope scope = fixture.authorizer.authorizeMutation(
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        );

        assertThat(scope.team()).isSameAs(fixture.team);
        assertThat(scope.season()).isSameAs(fixture.season);
        InOrder order = inOrder(fixture.repository);
        order.verify(fixture.repository).findTeamByIdWithSharedLock(TEAM_ID);
        order.verify(fixture.repository)
                .findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID);
    }

    @DisplayName("시즌 수정 권한은 팀 공유 잠금 뒤 시즌 배타 잠금을 획득한다")
    @Test
    void authorizesSeasonUpdateWithSharedTeamAndExclusiveSeasonLocks() {
        AuthorizationFixture fixture = fixture();
        when(fixture.repository.findTeamByIdWithSharedLock(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.repository.findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        fixture.authorizer.authorizeSeasonForUpdate(TEAM_ID, SEASON_ID, ACCESS_KEY);

        InOrder order = inOrder(fixture.repository);
        order.verify(fixture.repository).findTeamByIdWithSharedLock(TEAM_ID);
        order.verify(fixture.repository).findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID);
    }

    @DisplayName("시즌 생명주기는 팀과 시즌 배타 잠금을 사용하고 종료 시즌도 다시 열 수 있다")
    @Test
    void authorizesSeasonLifecycleWithExclusiveLocksForEndedSeason() {
        AuthorizationFixture fixture = fixture();
        fixture.season.updateEnding(true, Instant.parse("2026-07-20T03:04:05Z"));
        when(fixture.repository.findTeamByIdForUpdate(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.repository.findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        fixture.authorizer.authorizeSeasonLifecycle(TEAM_ID, SEASON_ID, ACCESS_KEY);

        InOrder order = inOrder(fixture.repository);
        order.verify(fixture.repository).findTeamByIdForUpdate(TEAM_ID);
        order.verify(fixture.repository).findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID);
    }

    @DisplayName("일반 변경 권한은 종료 시즌을 잠금과 접근 키 확인 뒤 거절한다")
    @Test
    void rejectsMutationForEndedSeason() {
        AuthorizationFixture fixture = fixture();
        fixture.season.updateEnding(true, Instant.parse("2026-07-20T03:04:05Z"));
        when(fixture.repository.findTeamByIdWithSharedLock(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.repository.findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        assertThatThrownBy(() ->
                fixture.authorizer.authorizeMutation(TEAM_ID, SEASON_ID, ACCESS_KEY))
                .isInstanceOf(SeasonEndedException.class);
    }

    private AuthorizationFixture fixture() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        WorkspaceAccessControl accessControl = new WorkspaceAccessControl("", "");
        Team team = Team.create(
                TEAM_ID,
                "알고리즘 한 바퀴",
                accessControl.hashAccessKey(ACCESS_KEY)
        );
        Season season = Season.create(
                SEASON_ID,
                TEAM_ID,
                "2026 여름 시즌",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 9, 30)
        );
        return new AuthorizationFixture(
                repository,
                team,
                season,
                new WorkspaceScopeAuthorizer(repository, accessControl)
        );
    }

    private record AuthorizationFixture(
            WorkspaceRepository repository,
            Team team,
            Season season,
            WorkspaceScopeAuthorizer authorizer
    ) {
    }
}
