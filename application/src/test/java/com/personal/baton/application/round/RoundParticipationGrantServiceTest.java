package com.personal.baton.application.round;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.MemberIdentityResult;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.IssuedRoundParticipationGrant;
import com.personal.baton.application.round.port.out.RoundGrantResourceRepository;
import com.personal.baton.application.round.port.out.RoundGrantResourceRepository.AuthorizedRoundResource;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort.ParticipantGrantCommand;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort.SignedParticipationGrant;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.identity.MemberIdentityRole;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RoundParticipationGrantServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T03:00:00Z");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TEAM_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SEASON_ID =
            UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MEMBER_ID =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ROLE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID RESOURCE_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String ROOM_URL =
            "https://round.example/room/abcd-efgh-jkmn";
    private static final String TOKEN = "header.payload.signature";

    @Mock
    private MemberIdentityUseCase memberIdentityUseCase;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private RoundGrantResourceRepository grantResourceRepository;

    @Mock
    private RoundParticipationGrantPort grantPort;

    private RoundParticipationGrantService service;

    @BeforeEach
    void setUp() {
        service = new RoundParticipationGrantService(
                memberIdentityUseCase,
                workspaceRepository,
                grantResourceRepository,
                grantPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @DisplayName("현재 활성 구성원과 요청 자료 소속을 확인한 뒤 participant 참여권을 발급한다")
    @Test
    void issuesGrantAfterMembershipAndResourceOwnershipChecks() {
        givenActiveMembership();
        givenOwnedResource(TEAM_ID, SEASON_ID);
        given(grantPort.issueParticipantGrant(command()))
                .willReturn(signedGrant(NOW.plusSeconds(300)));

        IssuedRoundParticipationGrant result = service.issue(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                account()
        );

        assertThat(result.token()).isEqualTo(TOKEN);
        assertThat(result.roomId()).isEqualTo("abcd-efgh-jkmn");
        assertThat(result.maxAgeSeconds()).isEqualTo(300);
        verify(grantPort).issueParticipantGrant(command());
    }

    @DisplayName("활성 구성원 결속이 없으면 저장 자료를 조회하지 않고 참여권을 거절한다")
    @Test
    void rejectsAccountWithoutActiveMembershipBeforeResourceLookup() {
        given(memberIdentityUseCase.findActiveMember(TEAM_ID, account()))
                .willReturn(Optional.empty());

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_GRANT_FORBIDDEN"
        );

        verifyNoInteractions(workspaceRepository, grantResourceRepository, grantPort);
    }

    @DisplayName("다른 팀이나 회차 역할에 속한 자료는 참여권 대상으로 숨긴다")
    @Test
    void rejectsResourceOwnedByAnotherTeamOrSeason() {
        givenActiveMembership();
        givenOwnedResource(
                UUID.fromString("77777777-7777-4777-8777-777777777777"),
                SEASON_ID
        );

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_RESOURCE_NOT_FOUND"
        );

        verifyNoInteractions(grantPort);
    }

    @DisplayName("요청 팀에 회차가 없으면 자료와 서명기를 조회하지 않는다")
    @Test
    void rejectsUnknownSeasonBeforeResourceLookup() {
        givenActiveMembership();
        given(workspaceRepository.findSeasonByTeamIdAndIdWithSharedLock(
                TEAM_ID,
                SEASON_ID
        )).willReturn(Optional.empty());

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_RESOURCE_NOT_FOUND"
        );

        verifyNoInteractions(grantPort);
    }

    @DisplayName("서명기가 5분을 넘는 결과를 돌려주면 참여권을 노출하지 않는다")
    @Test
    void rejectsSignerResultExceedingMaximumLifetime() {
        givenActiveMembership();
        givenOwnedResource(TEAM_ID, SEASON_ID);
        given(grantPort.issueParticipantGrant(command()))
                .willReturn(signedGrant(NOW.plusSeconds(301)));

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_GRANT_SIGNER_UNAVAILABLE"
        );
    }

    @DisplayName("복사한 room 경로는 활성 결속으로 접근 가능한 역할 자료가 하나일 때 발급한다")
    @Test
    void issuesFallbackGrantForExactlyOneAuthorizedResource() {
        given(grantPort.canonicalResourceUrl("abcd-efgh-jkmn"))
                .willReturn(ROOM_URL);
        given(grantResourceRepository.findAuthorizedResourcesByAccountIdAndUrl(
                ACCOUNT_ID,
                ROOM_URL
        )).willReturn(List.of(authorizedResource(RESOURCE_ID, ROOM_URL)));
        given(grantPort.issueParticipantGrant(command()))
                .willReturn(signedGrant(NOW.plusSeconds(300)));

        IssuedRoundParticipationGrant result = service.issueForRoom(
                "abcd-efgh-jkmn",
                account()
        );

        assertThat(result.roomId()).isEqualTo("abcd-efgh-jkmn");
        assertThat(result.maxAgeSeconds()).isEqualTo(300);
        verify(grantPort).issueParticipantGrant(command());
    }

    @DisplayName("접근 가능한 room 역할 자료가 없으면 존재 여부를 드러내지 않는 403으로 거절한다")
    @Test
    void rejectsFallbackWhenNoAuthorizedResourceExists() {
        given(grantPort.canonicalResourceUrl("abcd-efgh-jkmn"))
                .willReturn(ROOM_URL);
        given(grantResourceRepository.findAuthorizedResourcesByAccountIdAndUrl(
                ACCOUNT_ID,
                ROOM_URL
        )).willReturn(List.of());

        assertCode(
                () -> service.issueForRoom("abcd-efgh-jkmn", account()),
                "ROUND_GRANT_FORBIDDEN"
        );
    }

    @DisplayName("DB collation이 같은 값으로 찾은 비정규 URL은 exact 비교에서 권한 후보에서 제외한다")
    @Test
    void rejectsFallbackCandidateThatIsNotExactCanonicalUrl() {
        given(grantPort.canonicalResourceUrl("abcd-efgh-jkmn"))
                .willReturn(ROOM_URL);
        given(grantResourceRepository.findAuthorizedResourcesByAccountIdAndUrl(
                ACCOUNT_ID,
                ROOM_URL
        )).willReturn(List.of(authorizedResource(
                RESOURCE_ID,
                "https://round.example/room/ABCD-EFGH-JKMN"
        )));

        assertCode(
                () -> service.issueForRoom("abcd-efgh-jkmn", account()),
                "ROUND_GRANT_FORBIDDEN"
        );
        verifyNoInteractions(memberIdentityUseCase);
    }

    @DisplayName("접근 가능한 room 역할 자료가 둘 이상이면 모호한 권한을 409로 거절한다")
    @Test
    void rejectsAmbiguousFallbackResources() {
        given(grantPort.canonicalResourceUrl("abcd-efgh-jkmn"))
                .willReturn(ROOM_URL);
        given(grantResourceRepository.findAuthorizedResourcesByAccountIdAndUrl(
                ACCOUNT_ID,
                ROOM_URL
        )).willReturn(List.of(
                authorizedResource(RESOURCE_ID, ROOM_URL),
                authorizedResource(
                        UUID.fromString("88888888-8888-4888-8888-888888888888"),
                        ROOM_URL
                )
        ));

        assertCode(
                () -> service.issueForRoom("abcd-efgh-jkmn", account()),
                "ROUND_RESOURCE_AMBIGUOUS"
        );
        verifyNoInteractions(memberIdentityUseCase);
    }

    private void givenActiveMembership() {
        given(memberIdentityUseCase.findActiveMember(TEAM_ID, account()))
                .willReturn(Optional.of(new MemberIdentityResult(
                        ACCOUNT_ID,
                        TEAM_ID,
                        MEMBER_ID,
                        NOW.minusSeconds(3600),
                        MemberIdentityRole.MEMBER
                )));
    }

    private void givenOwnedResource(UUID roleTeamId, UUID roleSeasonId) {
        given(workspaceRepository.findSeasonByTeamIdAndIdWithSharedLock(
                TEAM_ID,
                SEASON_ID
        )).willReturn(Optional.of(Season.create(
                SEASON_ID,
                TEAM_ID,
                "2026 여름",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31)
        )));
        given(workspaceRepository.findRoleResourceById(RESOURCE_ID))
                .willReturn(Optional.of(RoleResource.create(
                        RESOURCE_ID,
                        ROLE_ID,
                        "ROUND 회의실",
                        ROOM_URL,
                        null
                )));
        given(workspaceRepository.findRoleById(ROLE_ID))
                .willReturn(Optional.of(Role.create(
                        ROLE_ID,
                        roleTeamId,
                        roleSeasonId,
                        "참여 역할",
                        "ROUND 회의에 참여합니다",
                        MEMBER_ID,
                        null,
                        null,
                        null,
                        List.of(),
                        null
                )));
    }

    private ParticipantGrantCommand command() {
        return new ParticipantGrantCommand(ACCOUNT_ID, SEASON_ID, ROOM_URL, NOW);
    }

    private SignedParticipationGrant signedGrant(Instant expiresAt) {
        return new SignedParticipationGrant(
                TOKEN,
                "abcd-efgh-jkmn",
                NOW,
                expiresAt
        );
    }

    private AuthorizedRoundResource authorizedResource(
            UUID resourceId,
            String resourceUrl
    ) {
        return new AuthorizedRoundResource(
                TEAM_ID,
                SEASON_ID,
                resourceId,
                resourceUrl
        );
    }

    private AuthenticatedAccount account() {
        return new AuthenticatedAccount(ACCOUNT_ID);
    }

    private void assertCode(Runnable invocation, String code) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(
                        RoundGrantOperationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code)
                );
    }
}
