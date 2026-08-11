package com.personal.baton.application.identity;

import com.personal.baton.application.identity.AccountView.LinkedIdentityView;
import com.personal.baton.application.identity.error.AccountNotFoundException;
import com.personal.baton.application.identity.error.EmailVerificationException;
import com.personal.baton.application.identity.error.IdentityConcurrentModificationException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.port.in.GetCurrentAccountUseCase;
import com.personal.baton.application.identity.port.in.LoadLocalCredentialUseCase;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase.UpdateLocalCredentialPasswordCommand;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase.VerifyLocalEmailCommand;
import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort.EmailVerificationDelivery;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.PlainPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.IdentityValidationException;
import com.personal.baton.domain.identity.LocalCredential;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IdentityService implements
        RegisterLocalAccountUseCase,
        VerifyLocalEmailUseCase,
        ResolveExternalLoginUseCase,
        LoadLocalCredentialUseCase,
        UpdateLocalCredentialPasswordUseCase,
        GetCurrentAccountUseCase {

    private static final Duration EMAIL_VERIFICATION_LIFETIME = Duration.ofMinutes(30);
    private static final int MINIMUM_PASSWORD_LENGTH = 12;
    private static final int MAXIMUM_PASSWORD_LENGTH = 128;
    private final IdentityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final StringKeyGenerator tokenGenerator;
    private final EmailVerificationOutboxPort emailVerificationOutboxPort;
    private final EmailVerificationOutboxPayloadProtector outboxPayloadProtector;
    private final ExternalLoginTransaction externalLoginTransaction;
    private final Clock clock;

    public IdentityService(
            IdentityRepository repository,
            PasswordEncoder passwordEncoder,
            StringKeyGenerator tokenGenerator,
            EmailVerificationOutboxPort emailVerificationOutboxPort,
            EmailVerificationOutboxPayloadProtector outboxPayloadProtector,
            ExternalLoginTransaction externalLoginTransaction,
            Clock clock
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.emailVerificationOutboxPort = emailVerificationOutboxPort;
        this.outboxPayloadProtector = outboxPayloadProtector;
        this.externalLoginTransaction = externalLoginTransaction;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LocalRegistrationResult registerLocalAccount(RegisterLocalAccountCommand command) {
        if (command == null) {
            throw new IdentityValidationException("로컬 계정 가입 요청은 필수입니다");
        }
        String email = AccountIdentity.normalizeLocalEmail(command.email());
        AccountIdentity existingIdentity = repository
                .findIdentity(IdentityProvider.LOCAL_EMAIL, email)
                .orElse(null);
        if (existingIdentity != null) {
            if (existingIdentity.isEmailVerified()) {
                throw new IdentityConflictException("이미 등록된 로컬 이메일 계정입니다");
            }
            return resumeLocalRegistration(email, existingIdentity);
        }

        Instant now = clock.instant();
        String verificationToken = tokenGenerator.generateKey();
        Instant expiresAt = now.plus(EMAIL_VERIFICATION_LIFETIME);

        Account account = Account.create(UUID.randomUUID(), command.displayName(), now);
        AccountIdentity identity = AccountIdentity.createLocal(
                UUID.randomUUID(),
                account.getId(),
                email,
                now
        );
        EmailVerificationChallenge challenge = EmailVerificationChallenge.create(
                UUID.randomUUID(),
                identity.getId(),
                VerificationTokenHash.hash(verificationToken),
                now,
                expiresAt
        );

        repository.saveAccount(account);
        repository.saveIdentity(identity);
        repository.saveEmailVerificationChallenge(challenge);
        enqueueVerificationDelivery(
                identity.getId(),
                challenge.getTokenHash(),
                account.getId(),
                email,
                verificationToken,
                expiresAt,
                now
        );

        return new LocalRegistrationResult(toAccountView(account, List.of(identity)), expiresAt);
    }

    private LocalRegistrationResult resumeLocalRegistration(
            String email,
            AccountIdentity discoveredIdentity
    ) {
        EmailVerificationChallenge challenge = repository
                .findEmailVerificationChallengeByIdentityIdForUpdate(discoveredIdentity.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "미검증 로컬 신원의 이메일 인증 요청을 찾을 수 없습니다"
                ));
        AccountIdentity identity = repository.findIdentityByIdForUpdate(discoveredIdentity.getId())
                .orElseThrow(() -> new IllegalStateException("로컬 신원을 찾을 수 없습니다"));
        if (identity.isEmailVerified()) {
            throw new IdentityConflictException("이미 등록된 로컬 이메일 계정입니다");
        }
        if (identity.getProvider() != IdentityProvider.LOCAL_EMAIL
                || !identity.getProviderSubject().equals(email)) {
            throw new IllegalStateException("로컬 신원 조회 결과가 가입 요청과 다릅니다");
        }
        if (repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()).isPresent()) {
            throw new IdentityConflictException(
                    "미검증 로컬 신원에는 비밀번호 자격 증명이 존재할 수 없습니다"
            );
        }
        Account account = findAccount(identity.getAccountId());
        Instant now = clock.instant();
        if (challenge.isPendingAt(now)) {
            return new LocalRegistrationResult(currentAccountView(account), challenge.getExpiresAt());
        }
        Instant expiresAt = now.plus(EMAIL_VERIFICATION_LIFETIME);
        String verificationToken = tokenGenerator.generateKey();

        challenge.reissue(
                VerificationTokenHash.hash(verificationToken),
                now,
                expiresAt
        );
        repository.saveEmailVerificationChallenge(challenge);
        enqueueVerificationDelivery(
                identity.getId(),
                challenge.getTokenHash(),
                account.getId(),
                email,
                verificationToken,
                expiresAt,
                now
        );
        return new LocalRegistrationResult(currentAccountView(account), expiresAt);
    }

    @Override
    @Transactional
    public LocalEmailVerificationResult verifyLocalEmail(VerifyLocalEmailCommand command) {
        if (command == null) {
            throw new IdentityValidationException("이메일 인증 요청은 필수입니다");
        }
        requireValidRawPassword(command.rawPassword());
        String tokenHash = VerificationTokenHash.hash(command.verificationToken());
        EmailVerificationChallenge challenge = repository
                .findEmailVerificationChallengeByTokenHashForUpdate(tokenHash)
                .orElseThrow(EmailVerificationException::new);
        AccountIdentity identity = repository.findIdentityByIdForUpdate(challenge.getIdentityId())
                .orElseThrow(() -> new IllegalStateException("이메일 인증 신원을 찾을 수 없습니다"));
        Instant now = clock.instant();
        if (identity.getProvider() != IdentityProvider.LOCAL_EMAIL
                || identity.isEmailVerified()) {
            throw new EmailVerificationException();
        }
        if (repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()).isPresent()) {
            throw new EmailVerificationException();
        }
        if (!challenge.consume(now)) {
            throw new EmailVerificationException();
        }
        String passwordHash = passwordEncoder.encode(command.rawPassword());
        LocalCredential credential = LocalCredential.create(identity.getId(), passwordHash, now);
        identity.verifyLocalEmail();
        repository.saveIdentity(identity);
        repository.saveLocalCredential(credential);
        repository.saveEmailVerificationChallenge(challenge);
        emailVerificationOutboxPort.supersedePending(identity.getId(), now);

        Account account = findAccount(identity.getAccountId());
        return new LocalEmailVerificationResult(currentAccountView(account), now);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExternalLoginResult resolveExternalLogin(ExternalLoginCommand command) {
        try {
            return externalLoginTransaction.resolve(command);
        } catch (IdentityConflictException | IdentityConcurrentModificationException ignored) {
            // The failed REQUIRES_NEW transaction is complete before this retry starts.
            // The converging transaction locks the committed provider-subject winner so
            // several duplicate callbacks serialize instead of racing on @Version again.
            return externalLoginTransaction.resolveAfterContention(command);
        }
    }

    @Override
    public Optional<LocalCredentialResult> loadLocalCredential(String email) {
        String normalizedEmail = AccountIdentity.normalizeLocalEmail(email);
        return repository.findIdentity(IdentityProvider.LOCAL_EMAIL, normalizedEmail)
                .flatMap(identity -> repository
                        .findLocalCredentialByIdentityId(identity.getId())
                        .map(credential -> new LocalCredentialResult(
                                identity.getAccountId(),
                                credential.getPasswordHash(),
                                identity.isEmailVerified()
                        )));
    }

    @Override
    @Transactional
    public void updateLocalCredentialPassword(UpdateLocalCredentialPasswordCommand command) {
        if (command == null || command.accountId() == null) {
            throw new IdentityValidationException("로컬 자격 증명 갱신 요청은 필수입니다");
        }
        AccountIdentity localIdentity = repository.findIdentitiesByAccountId(command.accountId())
                .stream()
                .filter(identity -> identity.getProvider() == IdentityProvider.LOCAL_EMAIL)
                .findFirst()
                .orElseThrow(AccountNotFoundException::new);
        LocalCredential credential = repository
                .findLocalCredentialByIdentityIdForUpdate(localIdentity.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "검증된 로컬 신원의 자격 증명을 찾을 수 없습니다"
                ));
        credential.replacePasswordHash(command.encodedPassword(), clock.instant());
        repository.saveLocalCredential(credential);
    }

    @Override
    public AccountView getCurrentAccount(UUID accountId) {
        return currentAccountView(findAccount(accountId));
    }

    private Account findAccount(UUID accountId) {
        if (accountId == null) {
            throw new AccountNotFoundException();
        }
        return repository.findAccountById(accountId).orElseThrow(AccountNotFoundException::new);
    }

    private void enqueueVerificationDelivery(
            UUID identityId,
            String challengeTokenHash,
            UUID accountId,
            String email,
            String verificationToken,
            Instant expiresAt,
            Instant enqueuedAt
    ) {
        ProtectionContext context = new ProtectionContext(
                identityId,
                accountId,
                challengeTokenHash,
                expiresAt
        );
        var protectedPayload = outboxPayloadProtector.protect(
                context,
                new PlainPayload(email, verificationToken)
        );
        emailVerificationOutboxPort.enqueueReplacingPending(
                context,
                protectedPayload,
                enqueuedAt
        );
    }

    private AccountView currentAccountView(Account account) {
        return toAccountView(account, repository.findIdentitiesByAccountId(account.getId()));
    }

    private AccountView toAccountView(Account account, List<AccountIdentity> identities) {
        List<LinkedIdentityView> linkedIdentities = identities.stream()
                .sorted(Comparator.comparing(identity -> identity.getProvider().name()))
                .map(identity -> new LinkedIdentityView(
                        identity.getProvider(),
                        identity.getEmailSnapshot(),
                        identity.isEmailVerified()
                ))
                .toList();
        return new AccountView(account.getId(), account.getDisplayName(), linkedIdentities);
    }

    private void requireValidRawPassword(String rawPassword) {
        if (rawPassword == null
                || rawPassword.length() < MINIMUM_PASSWORD_LENGTH
                || rawPassword.length() > MAXIMUM_PASSWORD_LENGTH) {
            throw new IdentityValidationException(
                    "비밀번호는 " + MINIMUM_PASSWORD_LENGTH + "자 이상 "
                            + MAXIMUM_PASSWORD_LENGTH + "자 이하여야 합니다"
            );
        }
    }

}
