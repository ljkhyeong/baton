package com.personal.baton.application.round;

import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.round.error.RoundGrantOperationException;
import com.personal.baton.application.round.port.in.RoundParticipationGrantUseCase.IssuedRoundParticipationGrant;
import com.personal.baton.application.round.port.out.RoundGrantResourceRepository;
import com.personal.baton.application.round.port.out.RoundGrantResourceRepository.AuthorizedRoundResource;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort.ParticipantGrantCommand;
import com.personal.baton.application.round.port.out.RoundParticipationGrantPort.SignedParticipationGrant;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.SessionAccount;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceQueryUseCase.RoleResourceResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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
    private static final UUID ROLE_ID =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID RESOURCE_ID =
            UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String ROOM_URL =
            "https://round.example/room/abcd-efgh-jkmn";
    private static final String TOKEN = "header.payload.signature";

    @Mock
    private WorkspaceUseCase workspaceUseCase;

    @Mock
    private RoundGrantResourceRepository grantResourceRepository;

    @Mock
    private RoundParticipationGrantPort grantPort;

    private RoundParticipationGrantService service;

    @BeforeEach
    void setUp() {
        service = new RoundParticipationGrantService(
                workspaceUseCase,
                grantResourceRepository,
                grantPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @DisplayName("현재 활성 구성원과 요청 자료 소속을 확인한 뒤 participant 참여권을 발급한다")
    @Test
    void issuesGrantAfterMembershipAndResourceOwnershipChecks() {
        givenAuthorizedResource();
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
        assertThat(result.refreshAfterSeconds()).isEqualTo(240);
        InOrder order = inOrder(workspaceUseCase, grantPort);
        order.verify(workspaceUseCase).getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        );
        order.verify(grantPort).issueParticipantGrant(command());
    }

    @DisplayName("ROUND 참여권 발급은 공유 잠금을 서명 완료까지 유지하는 읽기 트랜잭션이다")
    @Test
    void keepsSharedLockTransactionThroughGrantSigning() throws NoSuchMethodException {
        Transactional direct = RoundParticipationGrantService.class
                .getMethod(
                        "issue",
                        UUID.class,
                        UUID.class,
                        UUID.class,
                        AuthenticatedAccount.class
                )
                .getAnnotation(Transactional.class);
        Transactional room = RoundParticipationGrantService.class
                .getMethod(
                        "issueForRoom",
                        String.class,
                        AuthenticatedAccount.class
                )
                .getAnnotation(Transactional.class);
        Transactional locatedRoom = RoundParticipationGrantService.class
                .getMethod(
                        "issueForRoom",
                        String.class,
                        UUID.class,
                        UUID.class,
                        UUID.class,
                        AuthenticatedAccount.class
                )
                .getAnnotation(Transactional.class);

        assertThat(direct).isNotNull();
        assertThat(direct.readOnly()).isTrue();
        assertThat(room).isNotNull();
        assertThat(room.readOnly()).isTrue();
        assertThat(locatedRoom).isNotNull();
        assertThat(locatedRoom.readOnly()).isTrue();
    }

    @DisplayName("활성 구성원 결속이 없으면 저장 자료를 조회하지 않고 참여권을 거절한다")
    @Test
    void rejectsAccountWithoutActiveMembershipBeforeResourceLookup() {
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willThrow(new WorkspaceAccessDeniedException());

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_GRANT_FORBIDDEN"
        );

        verifyNoInteractions(grantResourceRepository, grantPort);
    }

    @DisplayName("다른 팀이나 회차 역할에 속한 자료는 참여권 대상으로 숨긴다")
    @Test
    void rejectsResourceOwnedByAnotherTeamOrSeason() {
        givenMissingResource();

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_RESOURCE_NOT_FOUND"
        );

        verifyNoInteractions(grantPort);
    }

    @DisplayName("요청 팀에 회차가 없으면 자료와 서명기를 조회하지 않는다")
    @Test
    void rejectsUnknownSeasonBeforeResourceLookup() {
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willThrow(new WorkspaceNotFoundException(
                "SEASON_NOT_FOUND",
                "시즌을 찾을 수 없습니다"
        ));

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_RESOURCE_NOT_FOUND"
        );

        verifyNoInteractions(grantPort);
    }

    @DisplayName("서명기가 5분을 넘는 결과를 돌려주면 참여권을 노출하지 않는다")
    @Test
    void rejectsSignerResultExceedingMaximumLifetime() {
        givenAuthorizedResource();
        given(grantPort.issueParticipantGrant(command()))
                .willReturn(signedGrant(NOW.plusSeconds(301)));

        assertCode(
                () -> service.issue(TEAM_ID, SEASON_ID, RESOURCE_ID, account()),
                "ROUND_GRANT_SIGNER_UNAVAILABLE"
        );
    }

    @DisplayName("서명기가 2초보다 짧은 수명을 돌려주면 안전한 갱신 여유가 없어 거절한다")
    @Test
    void rejectsSignerResultWithoutRefreshMargin() {
        givenAuthorizedResource();
        given(grantPort.issueParticipantGrant(command()))
                .willReturn(signedGrant(NOW.plusSeconds(1)));

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
        givenAuthorizedResource();
        given(grantPort.issueParticipantGrant(command()))
                .willReturn(signedGrant(NOW.plusSeconds(300)));

        IssuedRoundParticipationGrant result = service.issueForRoom(
                "abcd-efgh-jkmn",
                account()
        );

        assertThat(result.roomId()).isEqualTo("abcd-efgh-jkmn");
        assertThat(result.maxAgeSeconds()).isEqualTo(300);
        assertThat(result.refreshAfterSeconds()).isEqualTo(240);
        verify(grantPort).issueParticipantGrant(command());
    }

    @DisplayName("입장 locator가 있으면 지정 자료와 room URL을 다시 확인해 참여권을 발급한다")
    @Test
    void issuesLocatedRoomGrantAfterExactResourceCheck() {
        given(grantPort.canonicalResourceUrl("abcd-efgh-jkmn"))
                .willReturn(ROOM_URL);
        givenAuthorizedResource();
        given(grantPort.issueParticipantGrant(command()))
                .willReturn(signedGrant(NOW.plusSeconds(300)));

        IssuedRoundParticipationGrant result = service.issueForRoom(
                "abcd-efgh-jkmn",
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                account()
        );

        assertThat(result.roomId()).isEqualTo("abcd-efgh-jkmn");
        assertThat(result.refreshAfterSeconds()).isEqualTo(240);
        verify(workspaceUseCase).getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        );
        verify(grantPort).issueParticipantGrant(command());
    }

    @DisplayName("입장 locator의 자료가 요청 room과 다르면 존재 여부를 숨기고 거절한다")
    @Test
    void rejectsLocatedResourceForDifferentRoom() {
        given(grantPort.canonicalResourceUrl("abcd-efgh-jkmn"))
                .willReturn(ROOM_URL);
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willReturn(new RoleResourceResult(
                RESOURCE_ID,
                ROLE_ID,
                "다른 ROUND 회의실",
                "https://round.example/room/qrst-uvwx-yz23",
                null,
                NOW
        ));

        assertCode(
                () -> service.issueForRoom(
                        "abcd-efgh-jkmn",
                        TEAM_ID,
                        SEASON_ID,
                        RESOURCE_ID,
                        account()
                ),
                "ROUND_GRANT_FORBIDDEN"
        );
        verifyNoInteractions(grantResourceRepository);
        verify(grantPort, never()).issueParticipantGrant(command());
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
        verifyNoInteractions(workspaceUseCase);
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
        verifyNoInteractions(workspaceUseCase);
    }

    @DisplayName("room 후보 조회 뒤 구성원이 비활성화되면 shared-lock 재검증에서 참여권을 거절한다")
    @Test
    void rejectsFallbackWhenMembershipIsRevokedBeforeGrantAuthorization() {
        given(grantPort.canonicalResourceUrl("abcd-efgh-jkmn"))
                .willReturn(ROOM_URL);
        given(grantResourceRepository.findAuthorizedResourcesByAccountIdAndUrl(
                ACCOUNT_ID,
                ROOM_URL
        )).willReturn(List.of(authorizedResource(RESOURCE_ID, ROOM_URL)));
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willThrow(new WorkspaceAccessDeniedException());

        assertCode(
                () -> service.issueForRoom("abcd-efgh-jkmn", account()),
                "ROUND_GRANT_FORBIDDEN"
        );
        verify(workspaceUseCase).getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        );
    }

    private void givenAuthorizedResource() {
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willReturn(resource());
    }

    private void givenMissingResource() {
        given(workspaceUseCase.getRoleResourceForGrantAuthorized(
                TEAM_ID,
                SEASON_ID,
                RESOURCE_ID,
                authorization()
        )).willThrow(new WorkspaceNotFoundException(
                "ROLE_RESOURCE_NOT_FOUND",
                "자료를 찾을 수 없습니다"
        ));
    }

    private RoleResourceResult resource() {
        return new RoleResourceResult(
                RESOURCE_ID,
                ROLE_ID,
                "ROUND 회의실",
                ROOM_URL,
                null,
                NOW
        );
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

    private SessionAccount authorization() {
        return new SessionAccount(account());
    }

    private void assertCode(Runnable invocation, String code) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(
                        RoundGrantOperationException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code)
                );
    }
}
