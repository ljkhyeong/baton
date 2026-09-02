package com.personal.baton.application.identity;

import static com.personal.baton.application.identity.LocalIdentityPolicy.EMAIL_CHALLENGE_LIFETIME;
import static com.personal.baton.application.identity.LocalIdentityPolicy.requireValidRawPassword;

import com.personal.baton.application.identity.error.AccountNotFoundException;
import com.personal.baton.application.identity.error.EmailVerificationException;
import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.port.in.RegisterLocalAccountUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase;
import com.personal.baton.application.identity.port.in.VerifyLocalEmailUseCase.VerifyLocalEmailCommand;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailChallengePurpose;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.IdentityValidationException;
import com.personal.baton.domain.identity.LocalCredential;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class LocalAccountRegistrationService implements
        RegisterLocalAccountUseCase,
        VerifyLocalEmailUseCase {

    private final IdentityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final StringKeyGenerator tokenGenerator;
    private final EmailVerificationOutboxPort outboxPort;
    private final EmailChallengeDeliveryRegistrar deliveryRegistrar;
    private final Clock clock;

    public LocalAccountRegistrationService(
            IdentityRepository repository,
            PasswordEncoder passwordEncoder,
            StringKeyGenerator tokenGenerator,
            EmailVerificationOutboxPort outboxPort,
            EmailChallengeDeliveryRegistrar deliveryRegistrar,
            Clock clock
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.outboxPort = outboxPort;
        this.deliveryRegistrar = deliveryRegistrar;
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
        Instant expiresAt = now.plus(EMAIL_CHALLENGE_LIFETIME);
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
        deliveryRegistrar.enqueue(
                identity.getId(),
                challenge.getTokenHash(),
                account.getId(),
                email,
                verificationToken,
                expiresAt,
                now
        );
        return new LocalRegistrationResult(AccountView.from(account, List.of(identity)), expiresAt);
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
        Instant expiresAt = now.plus(EMAIL_CHALLENGE_LIFETIME);
        String verificationToken = tokenGenerator.generateKey();
        challenge.reissue(VerificationTokenHash.hash(verificationToken), now, expiresAt);
        repository.saveEmailVerificationChallenge(challenge);
        deliveryRegistrar.enqueue(
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
                || identity.isEmailVerified()
                || challenge.getPurpose() != EmailChallengePurpose.REGISTRATION) {
            throw new EmailVerificationException();
        }
        if (repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()).isPresent()) {
            throw new EmailVerificationException();
        }
        if (!challenge.consume(now)) {
            throw new EmailVerificationException();
        }
        LocalCredential credential = LocalCredential.create(
                identity.getId(),
                passwordEncoder.encode(command.rawPassword()),
                now
        );
        identity.verifyLocalEmail();
        repository.saveIdentity(identity);
        repository.saveLocalCredential(credential);
        repository.saveEmailVerificationChallenge(challenge);
        outboxPort.supersedePending(identity.getId(), now);

        Account account = findAccount(identity.getAccountId());
        return new LocalEmailVerificationResult(currentAccountView(account), now);
    }

    private Account findAccount(UUID accountId) {
        return repository.findAccountById(accountId).orElseThrow(AccountNotFoundException::new);
    }

    private AccountView currentAccountView(Account account) {
        return AccountView.from(account, repository.findIdentitiesByAccountId(account.getId()));
    }
}
