package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.AcceptedMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssueMemberInvitationCommand;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssuedMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.PreviewedMemberInvitation;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository.InvitationInsertResult;
import com.personal.baton.application.identity.port.out.MemberInvitationRepository.InvitationObservation;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.MemberInvitation;
import com.personal.baton.domain.identity.UserAccount;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Team;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberInvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final UUID TEAM_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OWNER_MEMBER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID TARGET_MEMBER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID OWNER_ACCOUNT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID INVITEE_ACCOUNT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final String IDEMPOTENCY_KEY =
            "2a000000-0000-4000-8000-000000000001";
    private static final String HMAC_SECRET =
            "invitation-hmac-secret-000000000000001";
    private static final String TEAM_ACCESS_KEY_HASH = "a".repeat(64);

    @Mock
    private IdentityRepository identityRepository;

    @Mock
    private MemberInvitationRepository invitationRepository;

    private MemberInvitationService service;

    @BeforeEach
    void setUp() {
        service = serviceWithTtl("PT24H");
    }

    @DisplayName("OWNER가 발급한 구성원 초대는 24시간 토큰과 해시만 영속화한다")
    @Test
    void issuesTwentyFourHourInvitationWithoutRawSecrets() {
        ArgumentCaptor<MemberInvitation> captor =
                ArgumentCaptor.forClass(MemberInvitation.class);
        prepareIssue();
        given(invitationRepository.insertIfAbsent(captor.capture()))
                .willAnswer(invocation -> new InvitationInsertResult(
                        invocation.getArgument(0),
                        true
                ));

        IssuedMemberInvitation result = issue(service);

        MemberInvitation stored = captor.getValue();
        assertThat(result.token())
                .hasSize(47)
                .startsWith("mi1_")
                .matches("mi1_[A-Za-z0-9_-]{43}");
        assertThat(result.issuedAt()).isEqualTo(NOW);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(24 * 60 * 60));
        assertThat(result.replayed()).isFalse();
        assertThat(stored.getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getIdempotencyKeyHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getTokenHash()).doesNotContain(result.token());
        assertThat(stored.getIdempotencyKeyHash()).doesNotContain(IDEMPOTENCY_KEY);
    }

    @DisplayName("동일한 멱등 발급 재생은 초대 상태가 끝났어도 같은 토큰과 시각을 반환한다")
    @Test
    void replaysSameIssueAfterInvitationReachedTerminalState() {
        IssuedFixture fixture = issuedFixture();
        fixture.invitation().consume(INVITEE_ACCOUNT_ID, NOW.plusSeconds(60));
        given(invitationRepository.findByIdempotencyKeyHash(anyString()))
                .willReturn(Optional.of(fixture.invitation()));

        IssuedMemberInvitation replay = issue(service);

        assertThat(replay)
                .usingRecursiveComparison()
                .ignoringFields("replayed")
                .isEqualTo(fixture.result());
        assertThat(replay.replayed()).isTrue();
    }

    @DisplayName("비활성화된 OWNER는 동일 멱등 키로도 원문 초대 토큰을 다시 얻을 수 없다")
    @Test
    void rejectsIssueReplayAfterOwnerDeactivation() {
        issuedFixture();
        Member inactiveOwner = member(OWNER_MEMBER_ID, "OWNER");
        inactiveOwner.updateDeactivation(true, NOW.plusSeconds(1));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                OWNER_MEMBER_ID
        )).willReturn(Optional.of(inactiveOwner));
        assertCode(() -> issue(service), "MEMBER_INVITATION_FORBIDDEN");
    }

    @DisplayName("구성원 초대 TTL은 7일까지 허용하고 그보다 길면 안전하게 거부한다")
    @Test
    void enforcesMaximumInvitationTtl() {
        MemberInvitationService sevenDays = serviceWithTtl("P7D");
        prepareIssue();
        given(invitationRepository.insertIfAbsent(any()))
                .willAnswer(invocation -> new InvitationInsertResult(
                        invocation.getArgument(0),
                        true
                ));

        assertThat(issue(sevenDays).expiresAt()).isEqualTo(NOW.plusSeconds(7 * 24 * 60 * 60));

        MemberInvitationService excessive = serviceWithTtl("PT169H");
        assertCode(() -> issue(excessive), "MEMBER_INVITATION_CONFIGURATION_INVALID");
    }

    @DisplayName("초대 미리보기는 현재 OWNER 권한과 수락 가능한 대상을 함께 확인한다")
    @Test
    void previewsAvailableInvitationWithoutMutatingIt() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository, invitationRepository);
        preparePreview(fixture.invitation());

        PreviewedMemberInvitation preview = service.preview(
                fixture.result().token(),
                account(INVITEE_ACCOUNT_ID)
        );

        assertThat(preview.teamId()).isEqualTo(TEAM_ID);
        assertThat(preview.teamName()).isEqualTo("BATON 팀");
        assertThat(preview.memberId()).isEqualTo(TARGET_MEMBER_ID);
        assertThat(preview.memberName()).isEqualTo("초대 대상");
        assertThat(preview.alreadyAccepted()).isFalse();
        assertThat(fixture.invitation().getConsumedAt()).isNull();
        verify(invitationRepository, never()).save(any());
    }

    @DisplayName("이미 같은 팀의 다른 구성원에 결속된 계정은 초대 미리보기에서 거부한다")
    @Test
    void rejectsPreviewWhenAccountAlreadyHasTeamBinding() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository, invitationRepository);
        preparePreview(fixture.invitation());
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                INVITEE_ACCOUNT_ID
        )).willReturn(Optional.of(MemberIdentityBinding.bind(
                UUID.fromString("10000000-0000-4000-8000-000000000006"),
                TEAM_ID,
                INVITEE_ACCOUNT_ID,
                NOW.minusSeconds(30)
        )));

        assertCode(
                () -> service.preview(
                        fixture.result().token(),
                        account(INVITEE_ACCOUNT_ID)
                ),
                "MEMBER_INVITATION_TARGET_UNAVAILABLE"
        );
    }

    @DisplayName("인증 계정이 유효한 초대를 수락하면 MEMBER 결속과 토큰 소비를 함께 저장한다")
    @Test
    void acceptsInvitationAndCreatesMemberBinding() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository, invitationRepository);
        prepareAccept(fixture);
        ArgumentCaptor<MemberIdentityBinding> bindingCaptor =
                ArgumentCaptor.forClass(MemberIdentityBinding.class);
        given(identityRepository.saveBinding(bindingCaptor.capture()))
                .willAnswer(invocation -> invocation.getArgument(0));

        AcceptedMemberInvitation accepted = service.accept(
                fixture.result().token(),
                account(INVITEE_ACCOUNT_ID)
        );

        assertThat(accepted.accountId()).isEqualTo(INVITEE_ACCOUNT_ID);
        assertThat(accepted.role()).isEqualTo(MemberIdentityRole.MEMBER);
        assertThat(bindingCaptor.getValue().getMemberId()).isEqualTo(TARGET_MEMBER_ID);
        assertThat(fixture.invitation().getConsumedAt()).isEqualTo(NOW);
        assertThat(fixture.invitation().getConsumedByAccountId())
                .isEqualTo(INVITEE_ACCOUNT_ID);
        verify(invitationRepository).save(fixture.invitation());
    }

    @DisplayName("잠금 대기 중 만료 시각을 넘긴 초대는 최종 잠금 뒤 다시 확인해 거부한다")
    @Test
    void rejectsInvitationThatExpiresWhileWaitingForLocks() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository, invitationRepository);
        MemberInvitation expiring = MemberInvitation.issue(
                fixture.invitation().getId(),
                TEAM_ID,
                TARGET_MEMBER_ID,
                OWNER_ACCOUNT_ID,
                fixture.invitation().getIdempotencyKeyHash(),
                fixture.invitation().getTokenHash(),
                NOW.minusSeconds(60),
                NOW.plusSeconds(1)
        );
        IssuedFixture expiringFixture = new IssuedFixture(fixture.result(), expiring);
        given(identityRepository.findUserAccountByIdForUpdate(INVITEE_ACCOUNT_ID))
                .willReturn(Optional.of(user(INVITEE_ACCOUNT_ID)));
        given(invitationRepository.findObservationByTokenHash(anyString()))
                .willReturn(Optional.of(observation(expiringFixture.invitation())));
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                OWNER_ACCOUNT_ID
        )).willReturn(Optional.of(ownerBinding()));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                OWNER_MEMBER_ID
        )).willReturn(Optional.of(member(OWNER_MEMBER_ID, "OWNER")));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                TARGET_MEMBER_ID
        )).willReturn(Optional.of(member(TARGET_MEMBER_ID, "초대 대상")));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(expiringFixture.invitation()));
        Clock advancingClock = mock(Clock.class);
        when(advancingClock.instant()).thenReturn(NOW, NOW.plusSeconds(2));
        MemberInvitationService advancingService = new MemberInvitationService(
                identityRepository,
                invitationRepository,
                advancingClock,
                HMAC_SECRET,
                "PT24H"
        );

        assertCode(
                () -> advancingService.accept(
                        fixture.result().token(),
                        account(INVITEE_ACCOUNT_ID)
                ),
                "MEMBER_INVITATION_EXPIRED"
        );
        verify(identityRepository, never()).saveBinding(any());
        verify(invitationRepository, never()).save(any());
    }

    @DisplayName("같은 계정의 수락 재시도는 발급 OWNER가 비활성화돼도 최초 결속을 반환한다")
    @Test
    void replaysAcceptanceWithoutRequiringCurrentIssuer() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository, invitationRepository);
        fixture.invitation().consume(INVITEE_ACCOUNT_ID, NOW.minusSeconds(10));
        MemberIdentityBinding binding = MemberIdentityBinding.bind(
                TARGET_MEMBER_ID,
                TEAM_ID,
                INVITEE_ACCOUNT_ID,
                NOW.minusSeconds(10)
        );
        given(identityRepository.findUserAccountByIdForUpdate(INVITEE_ACCOUNT_ID))
                .willReturn(Optional.of(user(INVITEE_ACCOUNT_ID)));
        given(invitationRepository.findObservationByTokenHash(anyString()))
                .willReturn(Optional.of(observation(fixture.invitation())));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(fixture.invitation()));
        given(identityRepository.findBindingByMemberId(TARGET_MEMBER_ID))
                .willReturn(Optional.of(binding));

        AcceptedMemberInvitation replay = service.accept(
                fixture.result().token(),
                account(INVITEE_ACCOUNT_ID)
        );

        assertThat(replay.boundAt()).isEqualTo(NOW.minusSeconds(10));
        verify(identityRepository, never())
                .findBindingByTeamIdAndUserAccountId(TEAM_ID, OWNER_ACCOUNT_ID);
        verify(identityRepository, never())
                .findMemberByTeamIdAndIdForUpdate(TEAM_ID, OWNER_MEMBER_ID);
    }

    @DisplayName("초대 폐기는 OWNER 권한을 먼저 확인하고 같은 요청을 안전하게 재생한다")
    @Test
    void revokesInvitationAfterOwnerAuthorization() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository, invitationRepository);
        given(identityRepository.findUserAccountByIdForUpdate(OWNER_ACCOUNT_ID))
                .willReturn(Optional.of(user(OWNER_ACCOUNT_ID)));
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                OWNER_ACCOUNT_ID
        )).willReturn(Optional.of(ownerBinding()));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                OWNER_MEMBER_ID
        )).willReturn(Optional.of(member(OWNER_MEMBER_ID, "OWNER")));
        given(invitationRepository.findByTeamIdAndIdForUpdate(
                TEAM_ID,
                fixture.invitation().getId()
        )).willReturn(Optional.of(fixture.invitation()));
        given(invitationRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        var first = service.revoke(
                TEAM_ID,
                fixture.invitation().getId(),
                account(OWNER_ACCOUNT_ID)
        );
        var replay = service.revoke(
                TEAM_ID,
                fixture.invitation().getId(),
                account(OWNER_ACCOUNT_ID)
        );

        assertThat(replay).isEqualTo(first);
        assertThat(first.revokedAt()).isEqualTo(NOW);
    }

    private IssuedFixture issuedFixture() {
        ArgumentCaptor<MemberInvitation> captor =
                ArgumentCaptor.forClass(MemberInvitation.class);
        prepareIssue();
        given(invitationRepository.insertIfAbsent(captor.capture()))
                .willAnswer(invocation -> new InvitationInsertResult(
                        invocation.getArgument(0),
                        true
                ));
        IssuedMemberInvitation result = issue(service);
        return new IssuedFixture(result, captor.getValue());
    }

    private void prepareIssue() {
        given(identityRepository.findUserAccountByIdForUpdate(OWNER_ACCOUNT_ID))
                .willReturn(Optional.of(user(OWNER_ACCOUNT_ID)));
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                OWNER_ACCOUNT_ID
        )).willReturn(Optional.of(ownerBinding()));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                OWNER_MEMBER_ID
        )).willReturn(Optional.of(member(OWNER_MEMBER_ID, "OWNER")));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                TARGET_MEMBER_ID
        )).willReturn(Optional.of(member(TARGET_MEMBER_ID, "초대 대상")));
        given(invitationRepository.findByIdempotencyKeyHash(anyString()))
                .willReturn(Optional.empty());
        given(identityRepository.findBindingByMemberId(TARGET_MEMBER_ID))
                .willReturn(Optional.empty());
        given(invitationRepository.findOpenByTeamIdAndMemberIdForUpdate(
                TEAM_ID,
                TARGET_MEMBER_ID,
                NOW
        )).willReturn(Optional.empty());
    }

    private void preparePreview(MemberInvitation invitation) {
        given(identityRepository.findUserAccountById(INVITEE_ACCOUNT_ID))
                .willReturn(Optional.of(user(INVITEE_ACCOUNT_ID)));
        given(invitationRepository.findByTokenHash(anyString()))
                .willReturn(Optional.of(invitation));
        given(identityRepository.findTeamById(TEAM_ID))
                .willReturn(Optional.of(Team.create(
                        TEAM_ID,
                        "BATON 팀",
                        TEAM_ACCESS_KEY_HASH
                )));
        given(identityRepository.findMemberByTeamIdAndId(
                TEAM_ID,
                TARGET_MEMBER_ID
        )).willReturn(Optional.of(member(TARGET_MEMBER_ID, "초대 대상")));
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                OWNER_ACCOUNT_ID
        )).willReturn(Optional.of(ownerBinding()));
        given(identityRepository.findMemberByTeamIdAndId(
                TEAM_ID,
                OWNER_MEMBER_ID
        )).willReturn(Optional.of(member(OWNER_MEMBER_ID, "OWNER")));
        given(identityRepository.findBindingByMemberId(TARGET_MEMBER_ID))
                .willReturn(Optional.empty());
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                INVITEE_ACCOUNT_ID
        )).willReturn(Optional.empty());
    }

    private void prepareAccept(IssuedFixture fixture) {
        given(identityRepository.findUserAccountByIdForUpdate(INVITEE_ACCOUNT_ID))
                .willReturn(Optional.of(user(INVITEE_ACCOUNT_ID)));
        given(invitationRepository.findObservationByTokenHash(anyString()))
                .willReturn(Optional.of(observation(fixture.invitation())));
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                OWNER_ACCOUNT_ID
        )).willReturn(Optional.of(ownerBinding()));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                OWNER_MEMBER_ID
        )).willReturn(Optional.of(member(OWNER_MEMBER_ID, "OWNER")));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(
                TEAM_ID,
                TARGET_MEMBER_ID
        )).willReturn(Optional.of(member(TARGET_MEMBER_ID, "초대 대상")));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(fixture.invitation()));
        given(identityRepository.findBindingByMemberId(TARGET_MEMBER_ID))
                .willReturn(Optional.empty());
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                INVITEE_ACCOUNT_ID
        )).willReturn(Optional.empty());
        given(invitationRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private MemberInvitationService serviceWithTtl(String ttl) {
        return new MemberInvitationService(
                identityRepository,
                invitationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                HMAC_SECRET,
                ttl
        );
    }

    private InvitationObservation observation(MemberInvitation invitation) {
        return new InvitationObservation(
                invitation.getId(),
                invitation.getTeamId(),
                invitation.getMemberId(),
                invitation.getIssuedByAccountId(),
                invitation.getExpiresAt(),
                invitation.getRevokedAt(),
                invitation.getConsumedAt()
        );
    }

    private IssuedMemberInvitation issue(MemberInvitationService targetService) {
        return targetService.issue(
                account(OWNER_ACCOUNT_ID),
                IDEMPOTENCY_KEY,
                new IssueMemberInvitationCommand(TEAM_ID, TARGET_MEMBER_ID)
        );
    }

    private AuthenticatedAccount account(UUID accountId) {
        return new AuthenticatedAccount(accountId);
    }

    private UserAccount user(UUID accountId) {
        return UserAccount.create(accountId, NOW.minusSeconds(600));
    }

    private Member member(UUID memberId, String name) {
        return Member.create(memberId, TEAM_ID, name);
    }

    private MemberIdentityBinding ownerBinding() {
        return MemberIdentityBinding.bind(
                OWNER_MEMBER_ID,
                TEAM_ID,
                OWNER_ACCOUNT_ID,
                NOW.minusSeconds(300),
                MemberIdentityRole.OWNER
        );
    }

    private void assertCode(Runnable invocation, String expectedCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(
                        IdentityOperationException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(expectedCode)
                );
    }

    private record IssuedFixture(
            IssuedMemberInvitation result,
            MemberInvitation invitation
    ) {
    }
}
