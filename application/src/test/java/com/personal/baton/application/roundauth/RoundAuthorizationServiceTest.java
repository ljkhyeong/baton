package com.personal.baton.application.roundauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.roundauth.error.RoundParticipationDeniedException;
import com.personal.baton.application.roundauth.error.RoundRoomConflictException;
import com.personal.baton.application.roundauth.error.RoundRoomNotFoundException;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.ClaimMembershipCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CreateRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CurrentMembershipQuery;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.CurrentRoomMappingsQuery;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.EndRoomMappingCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.IssueParticipationGrantCommand;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoundRoomHint;
import com.personal.baton.application.roundauth.port.in.RoundAuthorizationUseCase.RoomMappingResult;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantJwkSetProvider;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner;
import com.personal.baton.application.roundauth.port.out.ParticipationGrantSigner.ParticipationGrantClaims;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.RoomMappingCreationResult;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository.MembershipClaimResult;
import com.personal.baton.application.roundauth.port.out.RoundRoomIdGenerator;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.roundauth.RoundRoomMapping;
import com.personal.baton.domain.roundauth.RoundRoomTombstone;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

@Tag("usecase")
class RoundAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:34:56.987654Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TEAM_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SEASON_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MEMBER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ROLE_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID RESOURCE_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID SECOND_RESOURCE_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final String ROOM_ID = "bcdf-ghjk-mnpq";
    private static final String SECOND_ROOM_ID = "cdef-ghjk-mnpq";

    private RoundAuthorizationRepository roundRepository;
    private WorkspacePeopleRepository peopleRepository;
    private WorkspaceRecordsRepository recordsRepository;
    private WorkspaceSeasonRepository seasonRepository;
    private VerifyWorkspaceAccessUseCase workspaceAccess;
    private RoundRoomIdGenerator roomIdGenerator;
    private ParticipationGrantSigner grantSigner;
    private ParticipationGrantJwkSetProvider jwkSetProvider;
    private RoundAuthorizationService service;

    @BeforeEach
    void setUp() {
        roundRepository = mock(RoundAuthorizationRepository.class);
        peopleRepository = mock(WorkspacePeopleRepository.class);
        recordsRepository = mock(WorkspaceRecordsRepository.class);
        seasonRepository = mock(WorkspaceSeasonRepository.class);
        workspaceAccess = mock(VerifyWorkspaceAccessUseCase.class);
        roomIdGenerator = mock(RoundRoomIdGenerator.class);
        grantSigner = mock(ParticipationGrantSigner.class);
        jwkSetProvider = mock(ParticipationGrantJwkSetProvider.class);
        service = new RoundAuthorizationService(
                roundRepository,
                peopleRepository,
                recordsRepository,
                seasonRepository,
                workspaceAccess,
                roomIdGenerator,
                grantSigner,
                jwkSetProvider,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        when(recordsRepository.findRoleResourceById(RESOURCE_ID))
                .thenReturn(Optional.of(resource()));
    }

    @Test
    @DisplayName("공개 JWK 조회는 ROUND 데이터베이스 비트랜잭션 전파 정책을 사용한다")
    void readsPublicJwkSetWithoutDatabaseTransaction() throws NoSuchMethodException {
        var method = RoundAuthorizationService.class.getMethod("readPublicJwkSetJson");
        var transactionAttribute = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(method, RoundAuthorizationService.class);

        assertThat(transactionAttribute).isNotNull();
        assertThat(transactionAttribute.getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("현재 계정 연결 조회는 팀 접근 키를 확인한 뒤 저장된 멤버십을 반환한다")
    void findsCurrentMembershipAfterVerifyingTeamReadAccess() {
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));

        var result = service.findCurrentMembership(new CurrentMembershipQuery(
                ACCOUNT_ID,
                TEAM_ID,
                "workspace-access-key"
        ));

        verify(workspaceAccess).verifyTeamRead(TEAM_ID, "workspace-access-key");
        assertThat(result).hasValueSatisfying(membership -> {
            assertThat(membership.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(membership.teamId()).isEqualTo(TEAM_ID);
            assertThat(membership.memberId()).isEqualTo(MEMBER_ID);
            assertThat(membership.claimedAt()).isEqualTo(NOW.minusSeconds(30));
        });
    }

    @Test
    @DisplayName("현재 계정 연결 조회는 팀 접근 키가 틀리면 멤버십 존재 여부를 노출하지 않는다")
    void rejectsCurrentMembershipLookupBeforeReadingMembership() {
        doThrow(new WorkspaceAccessDeniedException()).when(workspaceAccess)
                .verifyTeamRead(TEAM_ID, "wrong-access-key");

        assertThatThrownBy(() -> service.findCurrentMembership(new CurrentMembershipQuery(
                ACCOUNT_ID,
                TEAM_ID,
                "wrong-access-key"
        ))).isInstanceOf(WorkspaceAccessDeniedException.class);

        verify(roundRepository, never()).findMembership(any(), any());
    }

    @Test
    @DisplayName("현재 ROUND 방 조회는 접근 키와 활성 멤버십을 한 번 확인하고 범위 매핑을 한 번에 반환한다")
    void findsCurrentRoomMappingsWithSingleAuthorityAndBatchLookup() {
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));
        when(peopleRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(activeMember()));
        RoundRoomMapping secondMapping = RoundRoomMapping.create(
                UUID.randomUUID(),
                SECOND_ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                SECOND_RESOURCE_ID,
                NOW.minusSeconds(20)
        );
        when(roundRepository.findMappingsByTeamIdAndSeasonId(TEAM_ID, SEASON_ID))
                .thenReturn(List.of(mapping(), secondMapping));

        var result = service.findCurrentRoomMappings(new CurrentRoomMappingsQuery(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                "workspace-access-key"
        ));

        verify(workspaceAccess, times(1)).verifyTeamRead(TEAM_ID, "workspace-access-key");
        verify(roundRepository, times(1)).findMembership(ACCOUNT_ID, TEAM_ID);
        verify(peopleRepository, times(1)).findMemberById(MEMBER_ID);
        verify(roundRepository, times(1))
                .findMappingsByTeamIdAndSeasonId(TEAM_ID, SEASON_ID);
        verify(roundRepository, never()).findMappingByResourceId(any());
        assertThat(result)
                .extracting(RoomMappingResult::roomId)
                .containsExactly(ROOM_ID, SECOND_ROOM_ID);
        assertThat(result).allSatisfy(mapping -> {
            assertThat(mapping.teamId()).isEqualTo(TEAM_ID);
            assertThat(mapping.seasonId()).isEqualTo(SEASON_ID);
            assertThat(mapping.endedAt()).isNull();
        });
    }

    @Test
    @DisplayName("현재 ROUND 방 조회는 잘못된 팀 접근 키에서 멤버십과 매핑 존재를 읽지 않는다")
    void rejectsCurrentRoomMappingLookupBeforeReadingAuthority() {
        doThrow(new WorkspaceAccessDeniedException()).when(workspaceAccess)
                .verifyTeamRead(TEAM_ID, "wrong-access-key");

        assertThatThrownBy(() -> service.findCurrentRoomMappings(new CurrentRoomMappingsQuery(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                "wrong-access-key"
        ))).isInstanceOf(WorkspaceAccessDeniedException.class);

        verify(roundRepository, never()).findMembership(any(), any());
        verify(roundRepository, never()).findMappingsByTeamIdAndSeasonId(any(), any());
    }

    @Test
    @DisplayName("인증 계정은 접근 키로 확인한 활성 구성원을 팀 멤버십으로 명시적으로 claim한다")
    void claimsActiveMemberForAuthenticatedAccount() {
        Member member = Member.create(MEMBER_ID, TEAM_ID, "스터디원");
        when(peopleRepository.findMemberById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID)).thenReturn(Optional.empty());
        when(roundRepository.claimMembership(any())).thenAnswer(invocation ->
                new MembershipClaimResult.Claimed(invocation.getArgument(0))
        );

        var result = service.claimMembership(new ClaimMembershipCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                "workspace-access-key"
        ));

        verify(workspaceAccess).verifyMutation(TEAM_ID, SEASON_ID, "workspace-access-key");
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.teamId()).isEqualTo(TEAM_ID);
        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.claimedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("같은 계정과 구성원의 동시 claim 패자는 승자의 멤버십 결과로 수렴한다")
    void convergesOnSameMembershipAfterClaimRace() {
        Member member = Member.create(MEMBER_ID, TEAM_ID, "스터디원");
        AccountTeamMembership winner = AccountTeamMembership.create(
                UUID.randomUUID(),
                ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID,
                NOW.minusMillis(1)
        );
        when(peopleRepository.findMemberById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID)).thenReturn(Optional.empty());
        when(roundRepository.claimMembership(any())).thenReturn(
                new MembershipClaimResult.AccountTeamAlreadyClaimed(winner)
        );

        var result = service.claimMembership(new ClaimMembershipCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                MEMBER_ID,
                "workspace-access-key"
        ));

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.claimedAt()).isEqualTo(NOW.minusMillis(1));
    }

    @Test
    @DisplayName("보관한 역할 자료에는 새 ROUND 방을 연결하지 않는다")
    void rejectsRoomMappingForArchivedResource() {
        RoleResource resource = resource();
        resource.updateArchive(true, NOW.minusSeconds(1));
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));
        when(peopleRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(activeMember()));
        when(recordsRepository.findRoleResourceById(RESOURCE_ID))
                .thenReturn(Optional.of(resource));

        assertThatThrownBy(() -> service.createRoomMapping(new CreateRoomMappingCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                "workspace-access-key"
        )))
                .isInstanceOf(RoundRoomConflictException.class)
                .hasMessageContaining("보관한 역할 자료");

        verify(roundRepository, never()).findMappingByResourceId(any());
        verify(roundRepository, never()).createMapping(any(), any());
    }

    @Test
    @DisplayName("room ID insert 경쟁이 여덟 번 이어지면 안정적인 방 충돌로 종료한다")
    void failsAfterEightRoomIdConflicts() {
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));
        when(peopleRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(activeMember()));
        when(recordsRepository.findRoleResourceById(RESOURCE_ID))
                .thenReturn(Optional.of(resource()));
        when(peopleRepository.findRoleById(ROLE_ID)).thenReturn(Optional.of(role()));
        when(roundRepository.findMappingByResourceId(RESOURCE_ID)).thenReturn(Optional.empty());
        when(roomIdGenerator.generate()).thenReturn("aaaa-aaaa-aaaa");
        when(roundRepository.createMapping(any(), any())).thenReturn(
                new RoomMappingCreationResult.RoomIdUnavailable()
        );

        assertThatThrownBy(() -> service.createRoomMapping(new CreateRoomMappingCommand(
                ACCOUNT_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                "workspace-access-key"
        )))
                .isInstanceOf(RoundRoomConflictException.class)
                .hasMessageContaining("고유한 ROUND 방 식별자");

        verify(roundRepository, times(8)).createMapping(any(), any());
    }

    @Test
    @DisplayName("종료된 방 재시도도 잘못된 workspace 접근 키를 허용하지 않는다")
    void rejectsEndedRoomReplayWithInvalidWorkspaceAccessKey() {
        RoundRoomTombstone tombstone = tombstone();
        tombstone.end(NOW.minusSeconds(10));
        when(roundRepository.findTombstoneForUpdate(ROOM_ID)).thenReturn(Optional.of(tombstone));
        doThrow(new WorkspaceAccessDeniedException()).when(workspaceAccess)
                .verifyMutation(TEAM_ID, SEASON_ID, "wrong-access-key");

        assertThatThrownBy(() -> service.endRoomMapping(new EndRoomMappingCommand(
                ACCOUNT_ID,
                ROOM_ID,
                "wrong-access-key"
        ))).isInstanceOf(WorkspaceAccessDeniedException.class);

        verify(roundRepository, never()).findMembership(any(), any());
        verify(roundRepository, never()).findMappingByRoomId(any());
    }

    @Test
    @DisplayName("참여권은 공급자 ID가 아닌 Account UUID를 sub claim으로 300초 동안 발급한다")
    void issuesShortLivedGrantForCanonicalAccount() {
        RoundRoomMapping mapping = mapping();
        when(roundRepository.findTombstoneForShare(ROOM_ID))
                .thenReturn(Optional.of(tombstone()));
        when(roundRepository.findMappingByRoomId(ROOM_ID)).thenReturn(Optional.of(mapping));
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));
        when(peopleRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(activeMember()));
        when(seasonRepository.findSeasonById(SEASON_ID)).thenReturn(Optional.of(activeSeason()));
        when(grantSigner.sign(any())).thenReturn("signed-participation-grant");

        var result = service.issueParticipationGrant(new IssueParticipationGrantCommand(
                ACCOUNT_ID,
                ROOM_ID,
                new RoundRoomHint(TEAM_ID, SEASON_ID, RESOURCE_ID)
        ));

        ArgumentCaptor<ParticipationGrantClaims> claimsCaptor =
                ArgumentCaptor.forClass(ParticipationGrantClaims.class);
        verify(grantSigner).sign(claimsCaptor.capture());
        ParticipationGrantClaims claims = claimsCaptor.getValue();
        assertThat(claims.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(claims.teamId()).isEqualTo(TEAM_ID);
        assertThat(claims.roomId()).isEqualTo(ROOM_ID);
        assertThat(claims.role()).isEqualTo("participant");
        assertThat(claims.issuedAt()).isEqualTo(Instant.parse("2026-08-08T12:34:56Z"));
        assertThat(claims.expiresAt()).isEqualTo(Instant.parse("2026-08-08T12:39:56Z"));
        assertThat(result.token()).isEqualTo("signed-participation-grant");
        assertThat(result.refreshAfterSeconds()).isEqualTo(240);
    }

    @Test
    @DisplayName("보관한 역할 자료의 기존 방도 새 참여권을 발급하지 않는다")
    void rejectsGrantForArchivedResource() {
        RoleResource resource = resource();
        resource.updateArchive(true, NOW.minusSeconds(1));
        when(recordsRepository.findRoleResourceById(RESOURCE_ID))
                .thenReturn(Optional.of(resource));
        when(roundRepository.findTombstoneForShare(ROOM_ID))
                .thenReturn(Optional.of(tombstone()));
        when(roundRepository.findMappingByRoomId(ROOM_ID)).thenReturn(Optional.of(mapping()));

        assertThatThrownBy(() -> service.issueParticipationGrant(
                new IssueParticipationGrantCommand(
                        ACCOUNT_ID,
                        ROOM_ID,
                        new RoundRoomHint(TEAM_ID, SEASON_ID, RESOURCE_ID)
                )
        )).isInstanceOf(RoundRoomNotFoundException.class);

        verify(roundRepository, never()).findMembership(any(), any());
        verify(grantSigner, never()).sign(any());
    }

    @Test
    @DisplayName("종료된 시즌은 유효한 멤버십과 방이 있어도 새 참여권을 발급하지 않는다")
    void rejectsGrantForEndedSeason() {
        Season endedSeason = activeSeason();
        endedSeason.updateEnding(true, NOW.minusSeconds(10));
        when(roundRepository.findTombstoneForShare(ROOM_ID))
                .thenReturn(Optional.of(tombstone()));
        when(roundRepository.findMappingByRoomId(ROOM_ID)).thenReturn(Optional.of(mapping()));
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));
        when(peopleRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(activeMember()));
        when(seasonRepository.findSeasonById(SEASON_ID)).thenReturn(Optional.of(endedSeason));

        assertThatThrownBy(() -> service.issueParticipationGrant(
                new IssueParticipationGrantCommand(ACCOUNT_ID, ROOM_ID, null)
        )).isInstanceOf(RoundParticipationDeniedException.class);
        verify(grantSigner, never()).sign(any());
    }

    @Test
    @DisplayName("클라이언트가 보낸 team/season/resource hint가 authoritative mapping과 다르면 방 존재를 숨긴다")
    void rejectsMismatchedRoomHint() {
        when(roundRepository.findTombstoneForShare(ROOM_ID))
                .thenReturn(Optional.of(tombstone()));
        when(roundRepository.findMappingByRoomId(ROOM_ID)).thenReturn(Optional.of(mapping()));
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));
        when(peopleRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(activeMember()));
        when(seasonRepository.findSeasonById(SEASON_ID)).thenReturn(Optional.of(activeSeason()));

        assertThatThrownBy(() -> service.issueParticipationGrant(
                new IssueParticipationGrantCommand(
                        ACCOUNT_ID,
                        ROOM_ID,
                        new RoundRoomHint(TEAM_ID, SEASON_ID, UUID.randomUUID())
                )
        )).isInstanceOf(RoundRoomNotFoundException.class);
        verify(grantSigner, never()).sign(any());
    }

    @Test
    @DisplayName("claim 뒤 비활성화된 구성원은 기존 membership으로 참여권을 갱신할 수 없다")
    void rejectsGrantWhenClaimedMemberWasDeactivated() {
        Member inactiveMember = activeMember();
        inactiveMember.updateDeactivation(true, NOW.minusSeconds(10));
        when(roundRepository.findTombstoneForShare(ROOM_ID))
                .thenReturn(Optional.of(tombstone()));
        when(roundRepository.findMappingByRoomId(ROOM_ID)).thenReturn(Optional.of(mapping()));
        when(roundRepository.findMembership(ACCOUNT_ID, TEAM_ID))
                .thenReturn(Optional.of(membership()));
        when(peopleRepository.findMemberById(MEMBER_ID))
                .thenReturn(Optional.of(inactiveMember));

        assertThatThrownBy(() -> service.issueParticipationGrant(
                new IssueParticipationGrantCommand(ACCOUNT_ID, ROOM_ID, null)
        )).isInstanceOf(RoundParticipationDeniedException.class);
        verify(grantSigner, never()).sign(any());
    }

    @Test
    @DisplayName("종료된 tombstone은 매핑이나 멤버십을 읽기 전에 새 참여권 발급을 거부한다")
    void rejectsGrantAfterRoomEnd() {
        RoundRoomTombstone ended = tombstone();
        ended.end(NOW.minusSeconds(1));
        when(roundRepository.findTombstoneForShare(ROOM_ID))
                .thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> service.issueParticipationGrant(
                new IssueParticipationGrantCommand(ACCOUNT_ID, ROOM_ID, null)
        )).isInstanceOf(RoundRoomNotFoundException.class);

        verify(roundRepository, never()).findMappingByRoomId(any());
        verify(roundRepository, never()).findMembership(any(), any());
        verify(grantSigner, never()).sign(any());
    }

    private AccountTeamMembership membership() {
        return AccountTeamMembership.create(
                UUID.randomUUID(),
                ACCOUNT_ID,
                TEAM_ID,
                MEMBER_ID,
                NOW.minusSeconds(30)
        );
    }

    private Member activeMember() {
        return Member.create(MEMBER_ID, TEAM_ID, "스터디원");
    }

    private RoundRoomMapping mapping() {
        return RoundRoomMapping.create(
                UUID.randomUUID(),
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                NOW.minusSeconds(30)
        );
    }

    private RoundRoomTombstone tombstone() {
        return RoundRoomTombstone.create(
                ROOM_ID,
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                NOW.minusSeconds(30)
        );
    }

    private Season activeSeason() {
        return Season.create(
                SEASON_ID,
                TEAM_ID,
                "2026 스터디",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 12, 31)
        );
    }

    private Role role() {
        return Role.create(
                ROLE_ID,
                TEAM_ID,
                SEASON_ID,
                "발표자",
                "스터디 발표를 진행한다",
                MEMBER_ID,
                null,
                LocalDate.of(2026, 8, 1),
                null,
                List.of("자료 준비"),
                null
        );
    }

    private RoleResource resource() {
        return RoleResource.create(
                RESOURCE_ID,
                ROLE_ID,
                "ROUND 스터디룸",
                "https://round.b4ton.com",
                null,
                NOW.minusSeconds(60)
        );
    }
}
