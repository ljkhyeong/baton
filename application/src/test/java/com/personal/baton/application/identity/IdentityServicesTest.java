package com.personal.baton.application.identity;

import com.personal.baton.application.identity.error.EmailVerificationException;
import com.personal.baton.application.identity.error.IdentityConcurrentModificationException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase.RegisterLocalAccountCommand;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginCommand;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginResult;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase.UpdateLocalCredentialPasswordCommand;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase.LocalCredentialResult;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase.VerifyLocalEmailCommand;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.PlainPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.IdentityValidationException;
import com.personal.baton.domain.identity.LocalCredential;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("usecase")
class IdentityServicesTest {

    private static final Instant NOW = Instant.parse("2026-08-08T01:02:03Z");
    private static final String RAW_PASSWORD = "correct horse battery staple";
    private static final String PASSWORD_HASH = "{bcrypt}$2a$10$opaque-encoded-password-value";
    private static final String VERIFICATION_TOKEN = "secure-email-verification-token-000000000001";
    private static final String VERIFICATION_TOKEN_HASH =
            "92accb91c1c58b9d231995fe4cea2aefe133a20464448de556b4c767643f9357";
    private static final ProtectedPayload PROTECTED_PAYLOAD = new ProtectedPayload(
            "encryptedPayloadValue000000000000000000000000000000",
            "nonceValue000000"
    );

    @DisplayName("자체 이메일 가입은 비밀번호 자격을 만들지 않고 아웃박스에만 원문 인증 토큰을 건넨다")
    @Test
    void registersLocalAccountWithoutPersistingRawSecrets() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        StringKeyGenerator tokenGenerator = mock(StringKeyGenerator.class);
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        EmailVerificationOutboxPayloadProtector payloadProtector = mock(
                EmailVerificationOutboxPayloadProtector.class
        );
        when(repository.findIdentity(
                IdentityProvider.LOCAL_EMAIL,
                "study.user@example.com"
        )).thenReturn(Optional.empty());
        when(tokenGenerator.generateKey()).thenReturn(VERIFICATION_TOKEN);
        when(payloadProtector.protect(any(), any())).thenReturn(PROTECTED_PAYLOAD);
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                tokenGenerator,
                outboxPort,
                payloadProtector
        );

        var result = service.registerLocalAccount(new RegisterLocalAccountCommand(
                " Study.User@Example.COM ",
                "스터디 사용자"
        ));

        ArgumentCaptor<EmailVerificationChallenge> challengeCaptor =
                ArgumentCaptor.forClass(EmailVerificationChallenge.class);
        ArgumentCaptor<PlainPayload> plainPayloadCaptor =
                ArgumentCaptor.forClass(PlainPayload.class);
        ArgumentCaptor<ProtectionContext> contextCaptor =
                ArgumentCaptor.forClass(ProtectionContext.class);
        verify(repository).saveEmailVerificationChallenge(challengeCaptor.capture());
        verify(payloadProtector).protect(contextCaptor.capture(), plainPayloadCaptor.capture());
        verify(outboxPort).enqueueReplacingPending(contextCaptor.getValue(), PROTECTED_PAYLOAD, NOW);

        verify(repository, never()).saveLocalCredential(any());
        verify(passwordEncoder, never()).encode(any());
        assertThat(challengeCaptor.getValue().getTokenHash())
                .hasSize(64)
                .doesNotContain(VERIFICATION_TOKEN);
        assertThat(contextCaptor.getValue().challengeTokenHash())
                .isEqualTo(challengeCaptor.getValue().getTokenHash());
        assertThat(plainPayloadCaptor.getValue().verificationToken()).isEqualTo(VERIFICATION_TOKEN);
        assertThat(plainPayloadCaptor.getValue().email()).isEqualTo("study.user@example.com");
        assertThat(plainPayloadCaptor.getValue().toString())
                .doesNotContain(VERIFICATION_TOKEN, "study.user@example.com");
        assertThat(new RegisterLocalAccountCommand(
                "study.user@example.com",
                "스터디 사용자"
        ).toString()).doesNotContain("study.user@example.com", "스터디 사용자");
        assertThat(result.verificationExpiresAt()).isEqualTo(NOW.plusSeconds(30 * 60));
        assertThat(result.account().accountId()).isNotNull();
        assertThat(result.account().identities()).singleElement()
                .extracting(AccountView.LinkedIdentityView::provider)
                .isEqualTo(IdentityProvider.LOCAL_EMAIL);
    }

    @DisplayName("만료된 자체 이메일 가입을 반복하면 이름은 유지하고 같은 Account에서 인증 도전만 재발급한다")
    @Test
    void reissuesUnverifiedLocalRegistrationOnSameAccount() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        StringKeyGenerator tokenGenerator = mock(StringKeyGenerator.class);
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        UUID accountId = UUID.randomUUID();
        Account account = Account.create(accountId, "이전 이름", NOW.minusSeconds(120));
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                accountId,
                "study.user@example.com",
                NOW.minusSeconds(120)
        );
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                identity.getId(),
                "a".repeat(64),
                NOW.minusSeconds(120),
                NOW.minusSeconds(60)
        );
        when(repository.findIdentity(
                IdentityProvider.LOCAL_EMAIL,
                "study.user@example.com"
        )).thenReturn(Optional.of(identity));
        when(repository.findEmailVerificationChallengeByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.of(challenge));
        when(repository.findIdentityByIdForUpdate(identity.getId())).thenReturn(Optional.of(identity));
        when(repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.empty());
        when(repository.findAccountById(accountId)).thenReturn(Optional.of(account));
        when(repository.findIdentitiesByAccountId(accountId)).thenReturn(List.of(identity));
        when(tokenGenerator.generateKey()).thenReturn(VERIFICATION_TOKEN);
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                tokenGenerator,
                outboxPort
        );

        var result = service.registerLocalAccount(new RegisterLocalAccountCommand(
                "STUDY.USER@EXAMPLE.COM",
                "새 이름"
        ));

        assertThat(result.account().accountId()).isEqualTo(accountId);
        assertThat(account.getDisplayName()).isEqualTo("이전 이름");
        assertThat(challenge.getTokenHash())
                .isEqualTo(VERIFICATION_TOKEN_HASH);
        assertThat(challenge.getExpiresAt()).isEqualTo(NOW.plusSeconds(30 * 60));
        verify(repository, never()).saveIdentity(any());
        verify(repository, never()).saveAccount(any());
        verify(repository, never()).saveLocalCredential(any());
        verify(passwordEncoder, never()).encode(any());
        verify(outboxPort).enqueueReplacingPending(any(), any(), any());
    }

    @DisplayName("미검증 신원에 비밀번호 자격이 이미 있으면 재가입이 기존 자격과 인증 도전을 바꾸지 않는다")
    @Test
    void rejectsRegistrationReissueWhenCredentialAlreadyExists() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        StringKeyGenerator tokenGenerator = mock(StringKeyGenerator.class);
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        UUID accountId = UUID.randomUUID();
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                accountId,
                "study.user@example.com",
                NOW.minusSeconds(120)
        );
        LocalCredential existingCredential = LocalCredential.create(
                identity.getId(),
                "{bcrypt}existing-opaque-value",
                NOW.minusSeconds(60)
        );
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                identity.getId(),
                "a".repeat(64),
                NOW.minusSeconds(120),
                NOW.plusSeconds(60)
        );
        when(repository.findIdentity(
                IdentityProvider.LOCAL_EMAIL,
                "study.user@example.com"
        )).thenReturn(Optional.of(identity));
        when(repository.findEmailVerificationChallengeByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.of(challenge));
        when(repository.findIdentityByIdForUpdate(identity.getId())).thenReturn(Optional.of(identity));
        when(repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.of(existingCredential));
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                tokenGenerator,
                outboxPort
        );

        assertThatThrownBy(() -> service.registerLocalAccount(new RegisterLocalAccountCommand(
                "study.user@example.com",
                "새 이름"
        ))).isInstanceOf(IdentityConflictException.class);

        assertThat(challenge.getTokenHash()).isEqualTo("a".repeat(64));
        verify(tokenGenerator, never()).generateKey();
        verify(passwordEncoder, never()).encode(any());
        verify(repository, never()).saveAccount(any());
        verify(repository, never()).saveLocalCredential(any());
        verify(repository, never()).saveEmailVerificationChallenge(any());
        verify(outboxPort, never()).enqueueReplacingPending(any(), any(), any());
    }

    @DisplayName("검증을 마친 자체 이메일 계정은 가입 반복으로 비밀번호나 인증 도전을 바꾸지 않는다")
    @Test
    void rejectsReregisteringVerifiedLocalAccountBeforeMutation() {
        IdentityRepository repository = mock(IdentityRepository.class);
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "verified@example.com",
                NOW.minusSeconds(60)
        );
        identity.verifyLocalEmail();
        when(repository.findIdentity(
                IdentityProvider.LOCAL_EMAIL,
                "verified@example.com"
        )).thenReturn(Optional.of(identity));
        LocalAccountRegistrationService service = service(repository);

        assertThatThrownBy(() -> service.registerLocalAccount(new RegisterLocalAccountCommand(
                "verified@example.com",
                "검증 사용자"
        ))).isInstanceOf(IdentityConflictException.class);
        verify(repository, never()).findEmailVerificationChallengeByIdentityIdForUpdate(any());
        verify(repository, never()).saveLocalCredential(any());
    }

    @DisplayName("외부 신원 유일 제약 또는 낙관적 잠금 충돌은 실패한 트랜잭션 밖에서 한 번 재시도한다")
    @Test
    void retriesConcurrentExternalLoginInFreshTransaction() {
        IdentityRepository repository = mock(IdentityRepository.class);
        ExternalLoginTransaction transaction = mock(ExternalLoginTransaction.class);
        var command = new ExternalLoginCommand(
                IdentityProvider.GOOGLE,
                "concurrent-subject",
                "same@example.com",
                true,
                "동시 사용자"
        );
        var winner = new ExternalLoginResult(
                new AccountView(UUID.randomUUID(), "동시 사용자", List.of(), 0),
                false
        );
        when(transaction.resolve(command)).thenThrow(new IdentityConcurrentModificationException(
                        "stale identity",
                        new RuntimeException("optimistic conflict")
                ));
        when(transaction.resolveAfterContention(command)).thenReturn(winner);
        AccountAuthenticationService service = authenticationService(repository, transaction);

        assertThat(service.resolveExternalLogin(command)).isSameAs(winner);

        verify(transaction).resolve(command);
        verify(transaction).resolveAfterContention(command);
    }

    @DisplayName("유효한 이메일 인증 토큰은 도전을 소비하고 검증 상태와 최초 비밀번호 자격을 함께 만든다")
    @Test
    void verifiesLocalEmailWithOneTimeChallenge() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UUID accountId = UUID.randomUUID();
        Account account = Account.create(accountId, "로컬 사용자", NOW.minusSeconds(60));
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                accountId,
                "local@example.com",
                NOW.minusSeconds(60)
        );
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                identity.getId(),
                VerificationTokenHash.hash(VERIFICATION_TOKEN),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60)
        );
        when(repository.findEmailVerificationChallengeByTokenHashForUpdate(
                VerificationTokenHash.hash(VERIFICATION_TOKEN)
        )).thenReturn(Optional.of(challenge));
        when(repository.findIdentityByIdForUpdate(identity.getId())).thenReturn(Optional.of(identity));
        when(repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.empty());
        when(repository.findAccountById(accountId)).thenReturn(Optional.of(account));
        when(repository.findIdentitiesByAccountId(accountId)).thenReturn(List.of(identity));
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(PASSWORD_HASH);
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                mock(StringKeyGenerator.class),
                mock(EmailVerificationOutboxPort.class)
        );

        var command = new VerifyLocalEmailCommand(VERIFICATION_TOKEN, RAW_PASSWORD);
        var result = service.verifyLocalEmail(command);

        assertThat(result.verifiedAt()).isEqualTo(NOW);
        assertThat(identity.isEmailVerified()).isTrue();
        assertThat(challenge.getConsumedAt()).isEqualTo(NOW);
        assertThat(command.toString()).doesNotContain(VERIFICATION_TOKEN, RAW_PASSWORD);
        ArgumentCaptor<LocalCredential> credentialCaptor =
                ArgumentCaptor.forClass(LocalCredential.class);
        verify(repository).saveIdentity(identity);
        verify(repository).saveLocalCredential(credentialCaptor.capture());
        verify(repository).saveEmailVerificationChallenge(challenge);
        assertThat(credentialCaptor.getValue().getIdentityId()).isEqualTo(identity.getId());
        assertThat(credentialCaptor.getValue().getPasswordHash()).isEqualTo(PASSWORD_HASH);
    }

    @DisplayName("정확한 만료 시각의 이메일 인증 토큰은 소비하지 않고 일반화된 오류로 거절한다")
    @Test
    void rejectsEmailVerificationAtExclusiveExpiry() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "local@example.com",
                NOW.minusSeconds(60)
        );
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                identity.getId(),
                VerificationTokenHash.hash(VERIFICATION_TOKEN),
                NOW.minusSeconds(60),
                NOW
        );
        when(repository.findEmailVerificationChallengeByTokenHashForUpdate(any()))
                .thenReturn(Optional.of(challenge));
        when(repository.findIdentityByIdForUpdate(identity.getId())).thenReturn(Optional.of(identity));
        when(repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.empty());
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                mock(StringKeyGenerator.class),
                mock(EmailVerificationOutboxPort.class)
        );

        assertThatThrownBy(() -> service.verifyLocalEmail(new VerifyLocalEmailCommand(
                VERIFICATION_TOKEN,
                RAW_PASSWORD
        )))
                .isInstanceOf(EmailVerificationException.class)
                .hasMessage("이메일 인증 요청이 올바르지 않거나 만료되었습니다");
        assertThat(challenge.getConsumedAt()).isNull();
        assertThat(identity.isEmailVerified()).isFalse();
        verify(passwordEncoder, never()).encode(any());
        verify(repository, never()).saveLocalCredential(any());
        verify(repository, never()).saveEmailVerificationChallenge(any());
    }

    @DisplayName("이메일 인증에서 사용할 최초 비밀번호가 정책보다 짧으면 저장소를 조회하지 않는다")
    @Test
    void rejectsInvalidInitialPasswordBeforeRepositoryAccess() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                mock(StringKeyGenerator.class),
                mock(EmailVerificationOutboxPort.class)
        );

        assertThatThrownBy(() -> service.verifyLocalEmail(new VerifyLocalEmailCommand(
                VERIFICATION_TOKEN,
                "too-short"
        ))).isInstanceOf(IdentityValidationException.class);

        verify(repository, never()).findEmailVerificationChallengeByTokenHashForUpdate(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @DisplayName("이미 비밀번호 자격이 있는 미검증 신원은 인증 토큰으로 기존 자격을 덮어쓰지 않는다")
    @Test
    void rejectsVerificationWhenCredentialAlreadyExists() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "local@example.com",
                NOW.minusSeconds(60)
        );
        LocalCredential existingCredential = LocalCredential.create(
                identity.getId(),
                "{bcrypt}existing-opaque-value",
                NOW.minusSeconds(30)
        );
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                identity.getId(),
                VerificationTokenHash.hash(VERIFICATION_TOKEN),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60)
        );
        when(repository.findEmailVerificationChallengeByTokenHashForUpdate(any()))
                .thenReturn(Optional.of(challenge));
        when(repository.findIdentityByIdForUpdate(identity.getId())).thenReturn(Optional.of(identity));
        when(repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.of(existingCredential));
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                mock(StringKeyGenerator.class),
                mock(EmailVerificationOutboxPort.class)
        );

        assertThatThrownBy(() -> service.verifyLocalEmail(new VerifyLocalEmailCommand(
                VERIFICATION_TOKEN,
                RAW_PASSWORD
        ))).isInstanceOf(EmailVerificationException.class);

        assertThat(challenge.getConsumedAt()).isNull();
        assertThat(identity.isEmailVerified()).isFalse();
        assertThat(existingCredential.getPasswordHash()).isEqualTo("{bcrypt}existing-opaque-value");
        verify(passwordEncoder, never()).encode(any());
        verify(repository, never()).saveIdentity(any());
        verify(repository, never()).saveLocalCredential(any());
        verify(repository, never()).saveEmailVerificationChallenge(any());
    }

    @DisplayName("이미 검증된 신원은 남은 인증 토큰으로 새 비밀번호 자격을 만들지 않는다")
    @Test
    void rejectsVerificationWhenIdentityAlreadyVerified() {
        IdentityRepository repository = mock(IdentityRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "local@example.com",
                NOW.minusSeconds(60)
        );
        identity.verifyLocalEmail();
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                identity.getId(),
                VerificationTokenHash.hash(VERIFICATION_TOKEN),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60)
        );
        when(repository.findEmailVerificationChallengeByTokenHashForUpdate(any()))
                .thenReturn(Optional.of(challenge));
        when(repository.findIdentityByIdForUpdate(identity.getId())).thenReturn(Optional.of(identity));
        LocalAccountRegistrationService service = service(
                repository,
                passwordEncoder,
                mock(StringKeyGenerator.class),
                mock(EmailVerificationOutboxPort.class)
        );

        assertThatThrownBy(() -> service.verifyLocalEmail(new VerifyLocalEmailCommand(
                VERIFICATION_TOKEN,
                RAW_PASSWORD
        ))).isInstanceOf(EmailVerificationException.class);

        assertThat(challenge.getConsumedAt()).isNull();
        verify(repository, never()).findLocalCredentialByIdentityIdForUpdate(any());
        verify(passwordEncoder, never()).encode(any());
        verify(repository, never()).saveLocalCredential(any());
    }

    @DisplayName("아직 인증하지 않아 비밀번호 자격이 없는 자체 이메일은 로그인 자격 조회에서 비어 있다")
    @Test
    void returnsEmptyWhenUnverifiedIdentityHasNoCredential() {
        IdentityRepository repository = mock(IdentityRepository.class);
        when(repository.findLocalLoginCredential("local@example.com"))
                .thenReturn(Optional.empty());
        AccountAuthenticationService service = authenticationService(repository);

        assertThat(service.loadLocalCredential("LOCAL@EXAMPLE.COM")).isEmpty();
    }

    @DisplayName("로컬 로그인 자격 조회는 이메일을 정규화하고 불투명 해시와 검증 상태만 반환한다")
    @Test
    void loadsLocalCredentialByNormalizedEmail() {
        IdentityRepository repository = mock(IdentityRepository.class);
        UUID accountId = UUID.randomUUID();
        when(repository.findLocalLoginCredential("local@example.com"))
                .thenReturn(Optional.of(new LocalCredentialResult(accountId, PASSWORD_HASH, true, 3)));
        AccountAuthenticationService service = authenticationService(repository);

        var result = service.loadLocalCredential(" LOCAL@EXAMPLE.COM ").orElseThrow();

        assertThat(result.accountId()).isEqualTo(accountId);
        assertThat(result.passwordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(result.emailVerified()).isTrue();
        assertThat(result.sessionVersion()).isEqualTo(3);
        assertThat(result.toString()).doesNotContain(PASSWORD_HASH);
    }

    @DisplayName("로그인 후 인코더 업그레이드는 로컬 자격 증명을 잠그고 새 불투명 해시로 교체한다")
    @Test
    void upgradesLocalCredentialPasswordHash() {
        IdentityRepository repository = mock(IdentityRepository.class);
        UUID accountId = UUID.randomUUID();
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                accountId,
                "local@example.com",
                NOW.minusSeconds(60)
        );
        identity.verifyLocalEmail();
        LocalCredential credential = LocalCredential.create(
                identity.getId(),
                PASSWORD_HASH,
                NOW.minusSeconds(60)
        );
        String upgradedHash = "{pbkdf2@SpringSecurity_v5_8}upgraded-opaque-password-value";
        when(repository.findIdentitiesByAccountId(accountId)).thenReturn(List.of(identity));
        when(repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()))
                .thenReturn(Optional.of(credential));
        AccountSecurityService service = securityService(repository);

        service.updateLocalCredentialPassword(
                new UpdateLocalCredentialPasswordCommand(accountId, PASSWORD_HASH, upgradedHash)
        );

        assertThat(credential.getPasswordHash()).isEqualTo(upgradedHash);
        assertThat(credential.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).saveLocalCredential(credential);
    }

    private LocalAccountRegistrationService service(IdentityRepository repository) {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn(PASSWORD_HASH);
        StringKeyGenerator tokenGenerator = mock(StringKeyGenerator.class);
        when(tokenGenerator.generateKey()).thenReturn(VERIFICATION_TOKEN);
        return service(
                repository,
                passwordEncoder,
                tokenGenerator,
                mock(EmailVerificationOutboxPort.class)
        );
    }

    private AccountAuthenticationService authenticationService(IdentityRepository repository) {
        return authenticationService(
                repository,
                mock(ExternalLoginTransaction.class)
        );
    }

    private AccountAuthenticationService authenticationService(
            IdentityRepository repository,
            ExternalLoginTransaction externalLoginTransaction
    ) {
        return new AccountAuthenticationService(
                repository,
                externalLoginTransaction
        );
    }

    private AccountSecurityService securityService(IdentityRepository repository) {
        EmailVerificationOutboxPort outboxPort = mock(EmailVerificationOutboxPort.class);
        return new AccountSecurityService(
                repository,
                mock(PasswordEncoder.class),
                mock(StringKeyGenerator.class),
                outboxPort,
                new EmailChallengeDeliveryRegistrar(
                        outboxPort,
                        mock(EmailVerificationOutboxPayloadProtector.class)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private LocalAccountRegistrationService service(
            IdentityRepository repository,
            PasswordEncoder passwordEncoder,
            StringKeyGenerator tokenGenerator,
            EmailVerificationOutboxPort outboxPort
    ) {
        EmailVerificationOutboxPayloadProtector payloadProtector = mock(
                EmailVerificationOutboxPayloadProtector.class
        );
        when(payloadProtector.protect(any(), any())).thenReturn(PROTECTED_PAYLOAD);
        return service(repository, passwordEncoder, tokenGenerator, outboxPort, payloadProtector);
    }

    private LocalAccountRegistrationService service(
            IdentityRepository repository,
            PasswordEncoder passwordEncoder,
            StringKeyGenerator tokenGenerator,
            EmailVerificationOutboxPort outboxPort,
            EmailVerificationOutboxPayloadProtector payloadProtector
    ) {
        return new LocalAccountRegistrationService(
                repository,
                passwordEncoder,
                tokenGenerator,
                outboxPort,
                new EmailChallengeDeliveryRegistrar(outboxPort, payloadProtector),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
