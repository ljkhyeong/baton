package com.personal.baton.application.workspace;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.MemberIdentityResult;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.SessionAccount;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.ConfirmRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateDecisionCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.Team;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkspaceSessionAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-07-31T03:00:00Z");
    private static final UUID TEAM_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SEASON_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MEMBER_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_MEMBER_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ROLE_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID RESOURCE_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");

    @Mock
    private WorkspaceRepository repository;

    @Mock
    private MemberIdentityUseCase memberIdentityUseCase;

    private WorkspaceService service;
    private Team team;
    private Season season;

    @BeforeEach
    void setUp() {
        service = new WorkspaceService(
                repository,
                memberIdentityUseCase,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "",
                ""
        );
        team = Team.create(TEAM_ID, "알고리즘 한 바퀴", "0".repeat(64));
        season = Season.create(
                SEASON_ID,
                TEAM_ID,
                "2026 여름",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        );
    }

    @DisplayName("활성 구성원에 결속된 로그인 세션은 공유 키 없이 워크스페이스를 조회한다")
    @Test
    void readsWorkspaceWithActiveSessionMembership() {
        given(repository.findTeamById(TEAM_ID)).willReturn(Optional.of(team));
        given(repository.findSeasonById(SEASON_ID)).willReturn(Optional.of(season));
        given(memberIdentityUseCase.findActiveMember(TEAM_ID, account()))
                .willReturn(Optional.of(membership()));
        given(repository.findMembersByTeamId(TEAM_ID)).willReturn(List.of());
        given(repository.findSeasonsByTeamId(TEAM_ID)).willReturn(List.of(season));
        given(repository.findRolesByTeamIdAndSeasonId(TEAM_ID, SEASON_ID))
                .willReturn(List.of());
        given(repository.findRoutinesBySeasonId(SEASON_ID)).willReturn(List.of());
        given(repository.findSeasonRoundsBySeasonId(SEASON_ID)).willReturn(List.of());
        given(repository.findDecisionsBySeasonId(SEASON_ID)).willReturn(List.of());

        var result = service.getWorkspaceAuthorized(
                TEAM_ID,
                SEASON_ID,
                sessionAuthorization()
        );

        assertThat(result.team().id()).isEqualTo(TEAM_ID);
        assertThat(result.season().id()).isEqualTo(SEASON_ID);
    }

    @DisplayName("로그인 세션의 워크스페이스 변경은 공유 잠금으로 확인한 활성 구성원만 허용한다")
    @Test
    void mutatesWorkspaceWithSharedLockedSessionMembership() {
        Member member = Member.create(MEMBER_ID, TEAM_ID, "박민서");
        given(repository.findTeamByIdWithSharedLock(TEAM_ID))
                .willReturn(Optional.of(team));
        given(repository.findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID))
                .willReturn(Optional.of(season));
        given(memberIdentityUseCase.findActiveMemberForMutation(TEAM_ID, account()))
                .willReturn(Optional.of(membership()));
        given(repository.findMemberById(MEMBER_ID)).willReturn(Optional.of(member));
        given(repository.saveMember(any(Member.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        var result = service.updateMemberDeactivationAuthorized(
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                sessionAuthorization(),
                true
        );

        assertThat(result.deactivatedAt()).isEqualTo(NOW);
        verify(memberIdentityUseCase).findActiveMemberForMutation(TEAM_ID, account());
    }

    @DisplayName("외부 권한 발급 자료 조회는 공유 잠금으로 활성 구성원을 다시 확인한다")
    @Test
    void readsGrantResourceWithSharedLockedSessionMembership() {
        RoleResource resource = RoleResource.create(
                RESOURCE_ID,
                ROLE_ID,
                "ROUND 회의실",
                "https://round.example/room/abcd-efgh-jkmn",
                null,
                NOW
        );
        Role role = Role.create(
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "진행자",
                "ROUND 회의를 진행합니다",
                MEMBER_ID,
                null,
                null,
                null,
                List.of(),
                null
        );
        given(repository.findTeamByIdWithSharedLock(TEAM_ID))
                .willReturn(Optional.of(team));
        given(repository.findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID))
                .willReturn(Optional.of(season));
        given(memberIdentityUseCase.findActiveMemberForMutation(TEAM_ID, account()))
                .willReturn(Optional.of(membership()));
        given(repository.findRoleResourceById(RESOURCE_ID))
                .willReturn(Optional.of(resource));
        given(repository.findRoleById(ROLE_ID))
                .willReturn(Optional.of(role));

        var result = service.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                sessionAuthorization()
        );

        assertThat(result.id()).isEqualTo(RESOURCE_ID);
        assertThat(result.url()).isEqualTo("https://round.example/room/abcd-efgh-jkmn");
        verify(memberIdentityUseCase).findActiveMemberForMutation(TEAM_ID, account());
        verify(memberIdentityUseCase, never()).findActiveMember(TEAM_ID, account());
    }

    @DisplayName("세션 결정 작성자와 바통 확인자는 현재 로그인 계정에 결속된 구성원과 일치해야 한다")
    @Test
    void rejectsDeclaredActorsDifferentFromActingSessionMember() {
        given(repository.findTeamByIdWithSharedLock(TEAM_ID))
                .willReturn(Optional.of(team));
        given(repository.findSeasonByTeamIdAndIdWithSharedLock(TEAM_ID, SEASON_ID))
                .willReturn(Optional.of(season));
        given(memberIdentityUseCase.findActiveMemberForMutation(TEAM_ID, account()))
                .willReturn(Optional.of(membership()));

        assertThatThrownBy(() -> service.createDecisionAuthorized(
                TEAM_ID,
                SEASON_ID,
                UUID.randomUUID().toString(),
                sessionAuthorization(),
                new CreateDecisionCommand(
                        "다음 주 운영 방식",
                        "역할을 명확히 나눕니다",
                        null,
                        OTHER_MEMBER_ID,
                        List.of()
                )
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        assertThatThrownBy(() -> service.updateDecisionAuthorized(
                TEAM_ID,
                SEASON_ID,
                UUID.randomUUID(),
                sessionAuthorization(),
                new UpdateDecisionCommand(
                        "다음 주 운영 방식",
                        "역할을 명확히 나눕니다",
                        null,
                        OTHER_MEMBER_ID,
                        List.of()
                )
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        UUID roleId = UUID.randomUUID();
        UUID handoffId = UUID.randomUUID();
        assertThatThrownBy(() -> service.transferRoleHandoffAuthorized(
                TEAM_ID,
                SEASON_ID,
                roleId,
                handoffId,
                sessionAuthorization(),
                new TransferRoleHandoffCommand(OTHER_MEMBER_ID, true)
        )).isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> service.acceptRoleHandoffAuthorized(
                TEAM_ID,
                SEASON_ID,
                roleId,
                handoffId,
                sessionAuthorization(),
                new ConfirmRoleHandoffCommand(OTHER_MEMBER_ID)
        )).isInstanceOf(WorkspaceAccessDeniedException.class);
        assertThatThrownBy(() -> service.cancelRoleHandoffAuthorized(
                TEAM_ID,
                SEASON_ID,
                roleId,
                handoffId,
                sessionAuthorization(),
                new ConfirmRoleHandoffCommand(OTHER_MEMBER_ID)
        )).isInstanceOf(WorkspaceAccessDeniedException.class);

        verify(repository, never()).saveDecision(any());
    }

    private AuthenticatedAccount account() {
        return new AuthenticatedAccount(ACCOUNT_ID);
    }

    private SessionAccount sessionAuthorization() {
        return new SessionAccount(account());
    }

    private MemberIdentityResult membership() {
        return new MemberIdentityResult(
                ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID,
                NOW.minusSeconds(60),
                MemberIdentityRole.MEMBER
        );
    }
}
