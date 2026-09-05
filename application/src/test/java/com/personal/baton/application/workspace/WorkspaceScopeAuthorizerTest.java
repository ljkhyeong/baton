package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.SeasonEndedException;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import com.personal.baton.domain.workspace.TeamPermission;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@Tag("policy")
class WorkspaceScopeAuthorizerTest {

    private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEASON_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACCESS_KEY = "workspace-access-key";

    @DisplayName("팀 읽기 권한은 시즌 상태를 조회하지 않고 팀 접근 키만 확인한다")
    @Test
    void authorizesTeamReadWithoutSeasonState() {
        AuthorizationFixture fixture = fixture();
        fixture.season.updateEnding(true, Instant.parse("2026-07-20T03:04:05Z"));
        when(fixture.accessRepository.findTeamById(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));

        Team team = fixture.authorizer.authorizeTeamRead(TEAM_ID, ACCESS_KEY);

        assertThat(team).isSameAs(fixture.team);
        verify(fixture.accessRepository).findTeamById(TEAM_ID);
        verifyNoMoreInteractions(fixture.accessRepository, fixture.seasonRepository);
    }

    @DisplayName("일반 변경 권한은 팀과 시즌의 공유 잠금을 순서대로 획득한다")
    @Test
    void authorizesMutationWithSharedTeamAndSeasonLocks() {
        AuthorizationFixture fixture = fixture();
        when(fixture.accessRepository.findTeamByIdWithSharedLock(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.seasonRepository.findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        WorkspaceScope scope = fixture.authorizer.authorizeMutation(
                TEAM_ID,
                SEASON_ID,
                ACCESS_KEY
        );

        assertThat(scope.team()).isSameAs(fixture.team);
        assertThat(scope.season()).isSameAs(fixture.season);
        InOrder order = inOrder(fixture.accessRepository, fixture.seasonRepository);
        order.verify(fixture.accessRepository).findTeamByIdWithSharedLock(TEAM_ID);
        order.verify(fixture.seasonRepository)
                .findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID);
    }

    @DisplayName("시즌 수정 권한은 팀 공유 잠금 뒤 시즌 배타 잠금을 획득한다")
    @Test
    void authorizesSeasonUpdateWithSharedTeamAndExclusiveSeasonLocks() {
        AuthorizationFixture fixture = fixture();
        when(fixture.accessRepository.findTeamByIdWithSharedLock(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.seasonRepository.findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        fixture.authorizer.authorizeSeasonForUpdate(TEAM_ID, SEASON_ID, ACCESS_KEY);

        InOrder order = inOrder(fixture.accessRepository, fixture.seasonRepository);
        order.verify(fixture.accessRepository).findTeamByIdWithSharedLock(TEAM_ID);
        order.verify(fixture.seasonRepository).findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID);
    }

    @DisplayName("시즌 생명주기는 팀과 시즌 배타 잠금을 사용하고 종료 시즌도 다시 열 수 있다")
    @Test
    void authorizesSeasonLifecycleWithExclusiveLocksForEndedSeason() {
        AuthorizationFixture fixture = fixture();
        fixture.season.updateEnding(true, Instant.parse("2026-07-20T03:04:05Z"));
        when(fixture.accessRepository.findTeamByIdForUpdate(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.seasonRepository.findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        fixture.authorizer.authorizeSeasonLifecycle(TEAM_ID, SEASON_ID, ACCESS_KEY);

        InOrder order = inOrder(fixture.accessRepository, fixture.seasonRepository);
        order.verify(fixture.accessRepository).findTeamByIdForUpdate(TEAM_ID);
        order.verify(fixture.seasonRepository).findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID);
    }

    @DisplayName("일반 변경 권한은 종료 시즌을 잠금과 접근 키 확인 뒤 거절한다")
    @Test
    void rejectsMutationForEndedSeason() {
        AuthorizationFixture fixture = fixture();
        fixture.season.updateEnding(true, Instant.parse("2026-07-20T03:04:05Z"));
        when(fixture.accessRepository.findTeamByIdWithSharedLock(TEAM_ID))
                .thenReturn(Optional.of(fixture.team));
        when(fixture.seasonRepository.findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));

        assertThatThrownBy(() ->
                fixture.authorizer.authorizeMutation(TEAM_ID, SEASON_ID, ACCESS_KEY))
                .isInstanceOf(SeasonEndedException.class);
    }

    @DisplayName("시즌 관리자 작업은 멤버십을 한 번 확인하고 관리자만 허용한다")
    @ParameterizedTest
    @EnumSource(TeamPermission.class)
    void checksAdministratorMembershipOnce(TeamPermission permission) {
        AuthorizationFixture fixture = fixture();
        fixture.team.enableAccountAccess();
        UUID accountId = UUID.randomUUID();
        Member member = Member.create(UUID.randomUUID(), TEAM_ID, "구성원");
        AccountTeamMembership membership = AccountTeamMembership.create(
                UUID.randomUUID(), accountId, TEAM_ID, member.getId(), Instant.EPOCH
        );
        membership.changePermission(permission);
        var memberships = mock(RoundAuthorizationRepository.class);
        var people = mock(WorkspacePeopleRepository.class);
        when(memberships.findMembership(accountId, TEAM_ID)).thenReturn(Optional.of(membership));
        when(people.findMemberById(member.getId())).thenReturn(Optional.of(member));
        when(fixture.accessRepository.findTeamByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(fixture.team));
        when(fixture.seasonRepository.findSeasonByTeamIdAndIdForUpdate(TEAM_ID, SEASON_ID))
                .thenReturn(Optional.of(fixture.season));
        var policy = new TeamAccountAccessPolicy(
                () -> Optional.of(accountId), memberships, people, mock(TeamAccessRepository.class)
        );
        var authorizer = new WorkspaceScopeAuthorizer(
                fixture.accessRepository, fixture.seasonRepository,
                new WorkspaceAccessControl(new WorkspaceSecrets("", "")), policy
        );

        if (permission == TeamPermission.ADMIN) {
            assertThat(authorizer.authorizeSeasonLifecycle(TEAM_ID, SEASON_ID, null).team())
                    .isSameAs(fixture.team);
        } else {
            assertThatThrownBy(() -> authorizer.authorizeSeasonLifecycle(TEAM_ID, SEASON_ID, null))
                    .isInstanceOf(WorkspaceAccessDeniedException.class);
        }
        verify(memberships).findMembership(accountId, TEAM_ID);
        verify(people).findMemberById(member.getId());
        verifyNoMoreInteractions(memberships, people);
    }

    private AuthorizationFixture fixture() {
        WorkspaceAccessRepository accessRepository = mock(WorkspaceAccessRepository.class);
        WorkspaceSeasonRepository seasonRepository = mock(WorkspaceSeasonRepository.class);
        WorkspaceAccessControl accessControl = new WorkspaceAccessControl(new WorkspaceSecrets("", ""));
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
                accessRepository,
                seasonRepository,
                team,
                season,
                new WorkspaceScopeAuthorizer(accessRepository, seasonRepository, accessControl, mock(TeamAccountAccessPolicy.class))
        );
    }

    private record AuthorizationFixture(
            WorkspaceAccessRepository accessRepository,
            WorkspaceSeasonRepository seasonRepository,
            Team team,
            Season season,
            WorkspaceScopeAuthorizer authorizer
    ) {
    }
}
