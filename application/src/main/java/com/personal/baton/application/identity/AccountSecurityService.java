package com.personal.baton.application.identity;

import static com.personal.baton.application.identity.LocalIdentityPolicy.EMAIL_CHALLENGE_LIFETIME;
import static com.personal.baton.application.identity.LocalIdentityPolicy.requireValidRawPassword;

import com.personal.baton.application.identity.error.AccountNotFoundException;
import com.personal.baton.application.identity.error.CurrentPasswordMismatchException;
import com.personal.baton.application.identity.error.LocalPasswordUnavailableException;
import com.personal.baton.application.identity.error.PasswordResetException;
import com.personal.baton.application.identity.port.in.AccountSecurityUseCase;
import com.personal.baton.application.identity.port.in.AccountSecurityUseCase.ChangeLocalPasswordCommand;
import com.personal.baton.application.identity.port.in.PasswordResetUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase;
import com.personal.baton.application.identity.port.in.UpdateLocalCredentialPasswordUseCase.UpdateLocalCredentialPasswordCommand;
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
import java.util.UUID;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountSecurityService implements
        PasswordResetUseCase,
        UpdateLocalCredentialPasswordUseCase,
        AccountSecurityUseCase {

    private final IdentityRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final StringKeyGenerator tokenGenerator;
    private final EmailVerificationOutboxPort outboxPort;
    private final EmailChallengeDeliveryRegistrar deliveryRegistrar;
    private final Clock clock;

    public AccountSecurityService(
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
    public AccountView getAccount(UUID accountId) {
        Account account = findAccount(accountId);
        return AccountView.from(account, repository.findIdentitiesByAccountId(account.getId()));
    }

    @Override
    @Transactional
    public void changeLocalPassword(ChangeLocalPasswordCommand command) {
        if (command == null || command.accountId() == null) {
            throw new IdentityValidationException("비밀번호 변경 요청은 필수입니다");
        }
        requireValidRawPassword(command.newRawPassword());
        AccountIdentity identity = findLocalIdentity(command.accountId());
        EmailVerificationChallenge challenge = repository
                .findEmailVerificationChallengeByIdentityIdForUpdate(identity.getId())
                .orElse(null);
        LocalCredential credential = repository
                .findLocalCredentialByIdentityIdForUpdate(identity.getId())
                .orElseThrow(LocalPasswordUnavailableException::new);
        if (!passwordEncoder.matches(command.currentRawPassword(), credential.getPasswordHash())) {
            throw new CurrentPasswordMismatchException();
        }

        Instant now = clock.instant();
        Account account = repository.findAccountByIdForUpdate(command.accountId())
                .orElseThrow(AccountNotFoundException::new);
        credential.replacePasswordHash(passwordEncoder.encode(command.newRawPassword()), now);
        account.invalidateSessions(now);
        repository.saveLocalCredential(credential);
        repository.saveAccount(account);
        if (challenge != null
                && challenge.getPurpose() == EmailChallengePurpose.PASSWORD_RESET
                && challenge.consume(now)) {
            repository.saveEmailVerificationChallenge(challenge);
        }
        outboxPort.supersedePending(identity.getId(), now);
    }

    @Override
    @Transactional
    public void revokeAllSessions(UUID accountId) {
        Account account = repository.findAccountByIdForUpdate(accountId)
                .orElseThrow(AccountNotFoundException::new);
        account.invalidateSessions(clock.instant());
        repository.saveAccount(account);
    }

    @Override
    @Transactional
    public void requestPasswordReset(String email) {
        String normalizedEmail = AccountIdentity.normalizeLocalEmail(email);
        AccountIdentity discoveredIdentity = repository
                .findIdentity(IdentityProvider.LOCAL_EMAIL, normalizedEmail)
                .orElse(null);
        if (discoveredIdentity == null) {
            return;
        }
        EmailVerificationChallenge challenge = repository
                .findEmailVerificationChallengeByIdentityIdForUpdate(discoveredIdentity.getId())
                .orElse(null);
        AccountIdentity identity = repository.findIdentityByIdForUpdate(discoveredIdentity.getId())
                .orElseThrow(AccountNotFoundException::new);
        if (repository.findAccountById(identity.getAccountId()).filter(Account::isActive).isEmpty()
                || !identity.isEmailVerified()
                || repository.findLocalCredentialByIdentityIdForUpdate(identity.getId()).isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        if (challenge != null
                && challenge.getPurpose() == EmailChallengePurpose.PASSWORD_RESET
                && challenge.isPendingAt(now)) {
            return;
        }
        String token = tokenGenerator.generateKey();
        String tokenHash = VerificationTokenHash.passwordResetHash(token);
        Instant expiresAt = now.plus(EMAIL_CHALLENGE_LIFETIME);
        if (challenge == null) {
            challenge = EmailVerificationChallenge.createForPasswordReset(
                    UUID.randomUUID(),
                    identity.getId(),
                    tokenHash,
                    now,
                    expiresAt
            );
        } else {
            challenge.reissueForPasswordReset(tokenHash, now, expiresAt);
        }
        repository.saveEmailVerificationChallenge(challenge);
        deliveryRegistrar.enqueue(
                identity.getId(),
                tokenHash,
                identity.getAccountId(),
                normalizedEmail,
                token,
                expiresAt,
                now
        );
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordCommand command) {
        requireValidRawPassword(command.rawPassword());
        EmailVerificationChallenge challenge = repository
                .findEmailVerificationChallengeByTokenHashForUpdate(
                        VerificationTokenHash.passwordResetHash(command.token())
                )
                .orElseThrow(PasswordResetException::new);
        if (challenge.getPurpose() != EmailChallengePurpose.PASSWORD_RESET) {
            throw new PasswordResetException();
        }
        AccountIdentity identity = repository.findIdentityByIdForUpdate(challenge.getIdentityId())
                .orElseThrow(PasswordResetException::new);
        if (identity.getProvider() != IdentityProvider.LOCAL_EMAIL || !identity.isEmailVerified()) {
            throw new PasswordResetException();
        }
        LocalCredential credential = repository
                .findLocalCredentialByIdentityIdForUpdate(identity.getId())
                .orElseThrow(PasswordResetException::new);
        Instant now = clock.instant();
        if (!challenge.consume(now)) {
            throw new PasswordResetException();
        }
        Account account = repository.findAccountByIdForUpdate(identity.getAccountId())
                .filter(Account::isActive).orElseThrow(PasswordResetException::new);
        credential.replacePasswordHash(passwordEncoder.encode(command.rawPassword()), now);
        account.invalidateSessions(now);
        repository.saveLocalCredential(credential);
        repository.saveAccount(account);
        repository.saveEmailVerificationChallenge(challenge);
        outboxPort.supersedePending(identity.getId(), now);
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
        if (!credential.getPasswordHash().equals(command.expectedPasswordHash())) {
            return;
        }
        credential.replacePasswordHash(command.encodedPassword(), clock.instant());
        repository.saveLocalCredential(credential);
    }

    private Account findAccount(UUID accountId) {
        if (accountId == null) {
            throw new AccountNotFoundException();
        }
        return repository.findAccountById(accountId).orElseThrow(AccountNotFoundException::new);
    }

    private AccountIdentity findLocalIdentity(UUID accountId) {
        return repository.findIdentitiesByAccountId(accountId).stream()
                .filter(identity -> identity.getProvider() == IdentityProvider.LOCAL_EMAIL)
                .findFirst()
                .orElseThrow(LocalPasswordUnavailableException::new);
    }
}
