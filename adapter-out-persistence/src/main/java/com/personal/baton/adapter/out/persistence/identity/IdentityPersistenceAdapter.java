package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.error.IdentityConcurrentModificationException;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import com.personal.baton.domain.identity.LocalCredential;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityPersistenceAdapter implements IdentityRepository {

    private final AccountJpaRepository accountRepository;
    private final AccountIdentityJpaRepository identityRepository;
    private final LocalCredentialJpaRepository credentialRepository;
    private final EmailVerificationChallengeJpaRepository challengeRepository;

    public IdentityPersistenceAdapter(
            AccountJpaRepository accountRepository,
            AccountIdentityJpaRepository identityRepository,
            LocalCredentialJpaRepository credentialRepository,
            EmailVerificationChallengeJpaRepository challengeRepository
    ) {
        this.accountRepository = accountRepository;
        this.identityRepository = identityRepository;
        this.credentialRepository = credentialRepository;
        this.challengeRepository = challengeRepository;
    }

    @Override
    public Account saveAccount(Account account) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "계정을 일시적으로 저장할 수 없습니다",
                () -> accountRepository.saveAndFlush(account)
        );
    }

    @Override
    public AccountIdentity saveIdentity(AccountIdentity identity) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "계정 신원을 일시적으로 저장할 수 없습니다",
                () -> {
                    try {
                        return identityRepository.saveAndFlush(identity);
                    } catch (OptimisticLockingFailureException exception) {
                        throw new IdentityConcurrentModificationException(
                                "계정 신원이 동시에 갱신됐습니다",
                                exception
                        );
                    } catch (DataIntegrityViolationException exception) {
                        if (hasConstraint(exception, "uk_account_identities_provider_subject")
                                || hasConstraint(
                                        exception,
                                        "uk_account_identities_account_provider"
                                )) {
                            throw new IdentityConflictException(
                                    "계정 신원이 이미 연결되어 있습니다",
                                    exception
                            );
                        }
                        throw exception;
                    }
                }
        );
    }

    @Override
    public LocalCredential saveLocalCredential(LocalCredential credential) {
        try {
            return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                    "로컬 자격 증명을 일시적으로 저장할 수 없습니다",
                    () -> credentialRepository.saveAndFlush(credential)
            );
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "primary")) {
                throw new IdentityConflictException("로컬 자격 증명이 이미 존재합니다", exception);
            }
            throw exception;
        }
    }

    @Override
    public EmailVerificationChallenge saveEmailVerificationChallenge(
            EmailVerificationChallenge challenge
    ) {
        try {
            return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                    "이메일 인증 요청을 일시적으로 저장할 수 없습니다",
                    () -> challengeRepository.saveAndFlush(challenge)
            );
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_email_verification_challenges_identity")
                    || hasConstraint(exception, "uk_email_verification_challenges_token_hash")) {
                throw new IdentityOperationUnavailableException(
                        "이메일 인증 요청이 경쟁했습니다",
                        exception
                );
            }
            throw exception;
        }
    }

    @Override
    public Optional<Account> findAccountById(UUID accountId) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "계정을 일시적으로 조회할 수 없습니다",
                () -> accountRepository.findById(accountId)
        );
    }

    @Override
    public Optional<AccountIdentity> findIdentity(
            IdentityProvider provider,
            String providerSubject
    ) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "계정 신원을 일시적으로 조회할 수 없습니다",
                () -> identityRepository.findByProviderAndProviderSubject(provider, providerSubject)
        );
    }

    @Override
    public Optional<AccountIdentity> findIdentityForUpdate(
            IdentityProvider provider,
            String providerSubject
    ) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "계정 신원을 일시적으로 잠글 수 없습니다",
                () -> identityRepository.findByProviderAndProviderSubjectForUpdate(
                        provider,
                        providerSubject
                )
        );
    }

    @Override
    public Optional<AccountIdentity> findIdentityByIdForUpdate(UUID identityId) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "계정 신원을 일시적으로 잠글 수 없습니다",
                () -> identityRepository.findByIdForUpdate(identityId)
        );
    }

    @Override
    public List<AccountIdentity> findIdentitiesByAccountId(UUID accountId) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "계정 신원을 일시적으로 조회할 수 없습니다",
                () -> identityRepository.findAllByAccountIdOrderByProviderAsc(accountId)
        );
    }

    @Override
    public Optional<LocalCredential> findLocalCredentialByIdentityId(UUID identityId) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "로컬 자격 증명을 일시적으로 조회할 수 없습니다",
                () -> credentialRepository.findById(identityId)
        );
    }

    @Override
    public Optional<LocalCredential> findLocalCredentialByIdentityIdForUpdate(UUID identityId) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "로컬 자격 증명을 일시적으로 잠글 수 없습니다",
                () -> credentialRepository.findByIdentityIdForUpdate(identityId)
        );
    }

    @Override
    public Optional<EmailVerificationChallenge> findEmailVerificationChallengeByIdentityIdForUpdate(
            UUID identityId
    ) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "이메일 인증 요청을 일시적으로 잠글 수 없습니다",
                () -> challengeRepository.findByIdentityIdForUpdate(identityId)
        );
    }

    @Override
    public Optional<EmailVerificationChallenge> findEmailVerificationChallengeByTokenHashForUpdate(
            String tokenHash
    ) {
        return IdentityDataAccessExceptionTranslator.translateTemporaryFailure(
                "이메일 인증 요청을 일시적으로 잠글 수 없습니다",
                () -> challengeRepository.findByTokenHashForUpdate(tokenHash)
        );
    }

    private boolean hasConstraint(Throwable throwable, String expectedName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null) {
                String actualName = constraintViolation.getConstraintName()
                        .replace("`", "")
                        .toLowerCase(Locale.ROOT);
                String normalizedExpectedName = expectedName.toLowerCase(Locale.ROOT);
                if (actualName.equals(normalizedExpectedName)
                        || actualName.endsWith("." + normalizedExpectedName)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
