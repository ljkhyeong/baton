package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.IdentityOperationException;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.AcceptedOwnerBootstrapInvitation;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssueOwnerBootstrapInvitationCommand;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssuedOwnerBootstrapInvitation;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.PreviewedOwnerBootstrapInvitation;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.identity.port.out.OwnerBootstrapInvitationRepository;
import com.personal.baton.application.identity.port.out.OwnerBootstrapInvitationRepository.InvitationInsertResult;
import com.personal.baton.domain.identity.MemberIdentityBinding;
import com.personal.baton.domain.identity.MemberIdentityRole;
import com.personal.baton.domain.identity.OwnerBootstrapInvitation;
import com.personal.baton.domain.identity.UserAccount;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Team;
import java.time.Clock;
import java.time.Duration;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OwnerBootstrapInvitationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private static final UUID TEAM_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_MEMBER_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID OTHER_ACCOUNT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000005");
    private static final String IDEMPOTENCY_KEY =
            "2a000000-0000-4000-8000-000000000001";
    private static final String BOOTSTRAP_KEY =
            "operator-bootstrap-key-0000000000000001";
    private static final String HMAC_SECRET =
            "invitation-hmac-secret-000000000000001";

    @Mock
    private IdentityRepository identityRepository;

    @Mock
    private OwnerBootstrapInvitationRepository invitationRepository;

    private OwnerBootstrapInvitationService service;

    @BeforeEach
    void setUp() {
        service = new OwnerBootstrapInvitationService(
                identityRepository,
                invitationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                settings(BOOTSTRAP_KEY, HMAC_SECRET, Duration.ofHours(1))
        );
    }

    @DisplayName("운영자 키로 발급한 bootstrap 초대는 한 시간 토큰과 해시만 영속화한다")
    @Test
    void issuesOneHourDeterministicInvitationWithoutRawSecrets() {
        ArgumentCaptor<OwnerBootstrapInvitation> invitationCaptor =
                ArgumentCaptor.forClass(OwnerBootstrapInvitation.class);
        prepareNewIssue(MEMBER_ID);
        given(invitationRepository.insertIfAbsent(invitationCaptor.capture()))
                .willAnswer(invocation -> new InvitationInsertResult(
                        invocation.getArgument(0),
                        true
                ));

        IssuedOwnerBootstrapInvitation result = issue(MEMBER_ID);

        OwnerBootstrapInvitation stored = invitationCaptor.getValue();
        assertThat(result.token()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(result.issuedAt()).isEqualTo(NOW);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(result.replayed()).isFalse();
        assertThat(stored.getTokenHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getIdempotencyKeyHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getTokenHash()).doesNotContain(result.token());
        assertThat(stored.getIdempotencyKeyHash()).doesNotContain(IDEMPOTENCY_KEY);
    }

    @DisplayName("동일한 멱등 키와 대상의 발급 재시도는 최초 시각과 동일 토큰을 반환한다")
    @Test
    void replaysIdenticalIssueWithSameToken() {
        ArgumentCaptor<OwnerBootstrapInvitation> invitationCaptor =
                ArgumentCaptor.forClass(OwnerBootstrapInvitation.class);
        prepareNewIssue(MEMBER_ID);
        given(invitationRepository.insertIfAbsent(invitationCaptor.capture()))
                .willAnswer(invocation -> new InvitationInsertResult(
                        invocation.getArgument(0),
                        true
                ));
        IssuedOwnerBootstrapInvitation first = issue(MEMBER_ID);
        given(invitationRepository.findByIdempotencyKeyHash(anyString()))
                .willReturn(Optional.of(invitationCaptor.getValue()));

        IssuedOwnerBootstrapInvitation replay = issue(MEMBER_ID);

        assertThat(replay)
                .usingRecursiveComparison()
                .ignoringFields("replayed")
                .isEqualTo(first);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        verify(identityRepository).findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID);
    }

    @DisplayName("동일한 멱등 키를 다른 구성원 bootstrap에 재사용하면 안정적인 충돌로 거부한다")
    @Test
    void rejectsIdempotencyKeyReuseForDifferentMember() {
        ArgumentCaptor<OwnerBootstrapInvitation> invitationCaptor =
                ArgumentCaptor.forClass(OwnerBootstrapInvitation.class);
        prepareNewIssue(MEMBER_ID);
        given(invitationRepository.insertIfAbsent(invitationCaptor.capture()))
                .willAnswer(invocation -> new InvitationInsertResult(
                        invocation.getArgument(0),
                        true
                ));
        issue(MEMBER_ID);
        given(invitationRepository.findByIdempotencyKeyHash(anyString()))
                .willReturn(Optional.of(invitationCaptor.getValue()));

        assertCode(
                () -> issue(OTHER_MEMBER_ID),
                "BOOTSTRAP_IDEMPOTENCY_KEY_REUSED"
        );
    }

    @DisplayName("canonical UUID가 아닌 멱등 키와 잘못된 운영자 키는 저장소 접근 전에 거부한다")
    @Test
    void rejectsMalformedIdempotencyAndUnauthorizedOperator() {
        assertCode(
                () -> service.issue(
                        "wrong-operator-key",
                        IDEMPOTENCY_KEY,
                        new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
                ),
                "BOOTSTRAP_INVITATION_FORBIDDEN"
        );
        assertCode(
                () -> service.issue(
                        BOOTSTRAP_KEY,
                        IDEMPOTENCY_KEY.toUpperCase(),
                        new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
                ),
                "INVALID_IDEMPOTENCY_KEY"
        );

        verifyNoInteractions(identityRepository, invitationRepository);
    }

    @DisplayName("bootstrap 비밀과 초대 수명 설정이 안전하지 않으면 발급을 중단한다")
    @Test
    void rejectsUnsafeIssuanceConfiguration() {
        OwnerBootstrapInvitationService missing = new OwnerBootstrapInvitationService(
                identityRepository,
                invitationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                settings("", "", Duration.ofHours(1))
        );
        OwnerBootstrapInvitationService reused = new OwnerBootstrapInvitationService(
                identityRepository,
                invitationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                settings(BOOTSTRAP_KEY, BOOTSTRAP_KEY, Duration.ofHours(1))
        );
        OwnerBootstrapInvitationService excessiveTtl = new OwnerBootstrapInvitationService(
                identityRepository,
                invitationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                settings(BOOTSTRAP_KEY, HMAC_SECRET, Duration.ofHours(2))
        );
        OwnerBootstrapInvitationService missingTtl = new OwnerBootstrapInvitationService(
                identityRepository,
                invitationRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                settings(BOOTSTRAP_KEY, HMAC_SECRET, null)
        );

        assertCode(
                () -> missing.issue(
                        BOOTSTRAP_KEY,
                        IDEMPOTENCY_KEY,
                        new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
                ),
                "BOOTSTRAP_CONFIGURATION_INVALID"
        );
        assertCode(
                () -> reused.issue(
                        BOOTSTRAP_KEY,
                        IDEMPOTENCY_KEY,
                        new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
                ),
                "BOOTSTRAP_CONFIGURATION_INVALID"
        );
        assertCode(
                () -> excessiveTtl.issue(
                        BOOTSTRAP_KEY,
                        IDEMPOTENCY_KEY,
                        new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
                ),
                "BOOTSTRAP_CONFIGURATION_INVALID"
        );
        assertCode(
                () -> missingTtl.issue(
                        BOOTSTRAP_KEY,
                        IDEMPOTENCY_KEY,
                        new IssueOwnerBootstrapInvitationCommand(TEAM_ID, MEMBER_ID)
                ),
                "BOOTSTRAP_CONFIGURATION_INVALID"
        );
    }

    private IdentityInvitationSettings settings(
            String bootstrapKey,
            String invitationHmacSecret,
            Duration bootstrapInvitationTtl
    ) {
        return new IdentityInvitationSettings(
                bootstrapKey,
                invitationHmacSecret,
                bootstrapInvitationTtl,
                Duration.ofHours(24)
        );
    }

    @DisplayName("이미 결속된 구성원이나 OWNER가 있는 팀에는 새 bootstrap 비밀을 발급하지 않는다")
    @Test
    void rejectsUnavailableBootstrapTargetBeforeIssuingSecret() {
        prepareNewIssue(MEMBER_ID);
        given(identityRepository.findBindingByMemberId(MEMBER_ID))
                .willReturn(Optional.of(MemberIdentityBinding.bind(
                        MEMBER_ID,
                        TEAM_ID,
                        ACCOUNT_ID,
                        NOW.minusSeconds(60)
                )));

        assertCode(
                () -> issue(MEMBER_ID),
                "BOOTSTRAP_TARGET_UNAVAILABLE"
        );

        given(identityRepository.findBindingByMemberId(MEMBER_ID))
                .willReturn(Optional.empty());
        given(identityRepository.findOwnerBindingByTeamId(TEAM_ID))
                .willReturn(Optional.of(MemberIdentityBinding.bind(
                        OTHER_MEMBER_ID,
                        TEAM_ID,
                        ACCOUNT_ID,
                        NOW.minusSeconds(60),
                        MemberIdentityRole.OWNER
                )));

        assertCode(
                () -> issue(MEMBER_ID),
                "BOOTSTRAP_TARGET_UNAVAILABLE"
        );

        verify(invitationRepository, never()).insertIfAbsent(any());
    }

    @DisplayName("인증 계정이 유효한 초대를 수락하면 OWNER 결속과 토큰 소비를 함께 저장한다")
    @Test
    void acceptsInvitationAndCreatesOwnerBinding() {
        IssuedFixture fixture = issuedFixture();
        prepareAccept(fixture, ACCOUNT_ID);
        ArgumentCaptor<MemberIdentityBinding> bindingCaptor =
                ArgumentCaptor.forClass(MemberIdentityBinding.class);
        given(identityRepository.saveBinding(bindingCaptor.capture()))
                .willAnswer(invocation -> invocation.getArgument(0));

        AcceptedOwnerBootstrapInvitation result = service.accept(
                fixture.result().token(),
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.teamId()).isEqualTo(TEAM_ID);
        assertThat(result.memberId()).isEqualTo(MEMBER_ID);
        assertThat(result.boundAt()).isEqualTo(NOW);
        assertThat(result.role()).isEqualTo(MemberIdentityRole.OWNER);
        assertThat(bindingCaptor.getValue().isOwner()).isTrue();
        assertThat(fixture.invitation().getConsumedAt()).isEqualTo(NOW);
        assertThat(fixture.invitation().getConsumedByAccountId()).isEqualTo(ACCOUNT_ID);
        verify(invitationRepository).save(fixture.invitation());
    }

    @DisplayName("bootstrap 초대 미리보기는 소비 전에 OWNER 대상과 현재 수락 가능성을 반환한다")
    @Test
    void previewsBootstrapInvitationBeforeConsumption() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository, invitationRepository);
        given(identityRepository.findUserAccountById(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(
                        ACCOUNT_ID,
                        NOW.minusSeconds(60)
                )));
        given(invitationRepository.findByTokenHash(anyString()))
                .willReturn(Optional.of(fixture.invitation()));
        given(identityRepository.findTeamById(TEAM_ID))
                .willReturn(Optional.of(Team.create(
                        TEAM_ID,
                        "BATON 팀",
                        "a".repeat(64)
                )));
        given(identityRepository.findMemberByTeamIdAndId(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(Member.create(
                        MEMBER_ID,
                        TEAM_ID,
                        "박민서"
                )));
        given(identityRepository.findBindingByMemberId(MEMBER_ID))
                .willReturn(Optional.empty());
        given(identityRepository.findBindingByTeamIdAndUserAccountId(
                TEAM_ID,
                ACCOUNT_ID
        )).willReturn(Optional.empty());
        given(identityRepository.findOwnerBindingByTeamId(TEAM_ID))
                .willReturn(Optional.empty());

        PreviewedOwnerBootstrapInvitation preview = service.preview(
                fixture.result().token(),
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(preview.teamName()).isEqualTo("BATON 팀");
        assertThat(preview.memberName()).isEqualTo("박민서");
        assertThat(preview.alreadyAccepted()).isFalse();
        assertThat(fixture.invitation().getConsumedAt()).isNull();
    }

    @DisplayName("같은 인증 계정의 초대 수락 재시도는 최초 OWNER 결속 결과를 반환한다")
    @Test
    void replaysAcceptanceForSameAccount() {
        IssuedFixture fixture = issuedFixture();
        MemberIdentityBinding binding = MemberIdentityBinding.bind(
                MEMBER_ID,
                TEAM_ID,
                ACCOUNT_ID,
                NOW.minusSeconds(30),
                MemberIdentityRole.OWNER
        );
        fixture.invitation().consume(ACCOUNT_ID, NOW.minusSeconds(20));
        given(identityRepository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(fixture.invitation()));
        given(identityRepository.findBindingByMemberId(MEMBER_ID))
                .willReturn(Optional.of(binding));

        AcceptedOwnerBootstrapInvitation firstReplay = service.accept(
                fixture.result().token(),
                new AuthenticatedAccount(ACCOUNT_ID)
        );
        AcceptedOwnerBootstrapInvitation secondReplay = service.accept(
                fixture.result().token(),
                new AuthenticatedAccount(ACCOUNT_ID)
        );

        assertThat(secondReplay).isEqualTo(firstReplay);
        assertThat(firstReplay.boundAt()).isEqualTo(NOW.minusSeconds(30));
        verify(identityRepository, never()).saveBinding(any());
    }

    @DisplayName("다른 계정이 이미 소비된 초대를 사용하면 사용 완료 오류를 반환한다")
    @Test
    void rejectsInvitationUsedByAnotherAccount() {
        IssuedFixture fixture = issuedFixture();
        clearInvocations(identityRepository);
        fixture.invitation().consume(ACCOUNT_ID, NOW.minusSeconds(10));
        given(identityRepository.findUserAccountByIdForUpdate(OTHER_ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(OTHER_ACCOUNT_ID, NOW.minusSeconds(60))));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(fixture.invitation()));

        assertCode(
                () -> service.accept(
                        fixture.result().token(),
                        new AuthenticatedAccount(OTHER_ACCOUNT_ID)
                ),
                "BOOTSTRAP_INVITATION_USED"
        );

        verify(identityRepository, never())
                .findMemberByTeamIdAndIdForUpdate(any(), any());
    }

    @DisplayName("만료되거나 폐기된 초대는 각각 구분되는 안정적인 오류를 반환한다")
    @Test
    void distinguishesExpiredAndRevokedInvitations() {
        IssuedFixture fixture = issuedFixture();
        OwnerBootstrapInvitation expired = OwnerBootstrapInvitation.issue(
                fixture.invitation().getId(),
                TEAM_ID,
                MEMBER_ID,
                fixture.invitation().getIdempotencyKeyHash(),
                fixture.invitation().getTokenHash(),
                NOW.minusSeconds(7200),
                NOW
        );
        given(identityRepository.findUserAccountByIdForUpdate(ACCOUNT_ID))
                .willReturn(Optional.of(UserAccount.create(ACCOUNT_ID, NOW.minusSeconds(60))));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(expired));

        assertCode(
                () -> service.accept(
                        fixture.result().token(),
                        new AuthenticatedAccount(ACCOUNT_ID)
                ),
                "BOOTSTRAP_INVITATION_EXPIRED"
        );

        fixture.invitation().revoke(NOW.minusSeconds(1));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(fixture.invitation()));

        assertCode(
                () -> service.accept(
                        fixture.result().token(),
                        new AuthenticatedAccount(ACCOUNT_ID)
                ),
                "BOOTSTRAP_INVITATION_REVOKED"
        );
    }

    private IssuedOwnerBootstrapInvitation issue(UUID memberId) {
        return service.issue(
                BOOTSTRAP_KEY,
                IDEMPOTENCY_KEY,
                new IssueOwnerBootstrapInvitationCommand(TEAM_ID, memberId)
        );
    }

    private void prepareNewIssue(UUID memberId) {
        given(invitationRepository.findByIdempotencyKeyHash(anyString()))
                .willReturn(Optional.empty());
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, memberId))
                .willReturn(Optional.of(Member.create(memberId, TEAM_ID, "박민서")));
        given(identityRepository.findBindingByMemberId(memberId))
                .willReturn(Optional.empty());
        given(identityRepository.findOwnerBindingByTeamId(TEAM_ID))
                .willReturn(Optional.empty());
    }

    private IssuedFixture issuedFixture() {
        ArgumentCaptor<OwnerBootstrapInvitation> invitationCaptor =
                ArgumentCaptor.forClass(OwnerBootstrapInvitation.class);
        prepareNewIssue(MEMBER_ID);
        given(invitationRepository.insertIfAbsent(invitationCaptor.capture()))
                .willAnswer(invocation -> new InvitationInsertResult(
                        invocation.getArgument(0),
                        true
                ));
        IssuedOwnerBootstrapInvitation result = issue(MEMBER_ID);
        return new IssuedFixture(result, invitationCaptor.getValue());
    }

    private void prepareAccept(IssuedFixture fixture, UUID accountId) {
        given(identityRepository.findUserAccountByIdForUpdate(accountId))
                .willReturn(Optional.of(UserAccount.create(accountId, NOW.minusSeconds(60))));
        given(invitationRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(fixture.invitation()));
        given(identityRepository.findMemberByTeamIdAndIdForUpdate(TEAM_ID, MEMBER_ID))
                .willReturn(Optional.of(Member.create(MEMBER_ID, TEAM_ID, "박민서")));
        given(identityRepository.findBindingByMemberId(MEMBER_ID))
                .willReturn(Optional.empty());
        given(identityRepository.findBindingByTeamIdAndUserAccountId(TEAM_ID, accountId))
                .willReturn(Optional.empty());
        given(identityRepository.findOwnerBindingByTeamId(TEAM_ID))
                .willReturn(Optional.empty());
        given(invitationRepository.save(any()))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private void assertCode(Runnable invocation, String code) {
        assertThatThrownBy(invocation::run).isInstanceOfSatisfying(
                IdentityOperationException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code)
        );
    }

    private record IssuedFixture(
            IssuedOwnerBootstrapInvitation result,
            OwnerBootstrapInvitation invitation
    ) {
    }
}
