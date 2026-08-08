package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.application.identity.error.IdentityConflictException;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.domain.identity.AccountIdentity;
import com.personal.baton.domain.identity.EmailVerificationChallenge;
import com.personal.baton.domain.identity.IdentityProvider;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityPersistenceAdapterTest {

    @Mock
    private AccountJpaRepository accountRepository;

    @Mock
    private AccountIdentityJpaRepository identityRepository;

    @Mock
    private LocalCredentialJpaRepository credentialRepository;

    @Mock
    private EmailVerificationChallengeJpaRepository challengeRepository;

    private IdentityPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new IdentityPersistenceAdapter(
                accountRepository,
                identityRepository,
                credentialRepository,
                challengeRepository
        );
    }

    @DisplayName("이메일 provider subject unique 경쟁은 열거 방지 가능한 신원 충돌로 변환한다")
    @Test
    void translatesProviderSubjectUniqueRaceToIdentityConflict() {
        AccountIdentity identity = mock(AccountIdentity.class);
        DataIntegrityViolationException cause = uniqueViolation(
                "baton.uk_account_identities_provider_subject"
        );
        when(identityRepository.saveAndFlush(identity)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveIdentity(identity))
                .isInstanceOfSatisfying(
                        IdentityConflictException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("행 잠금 시간 초과는 신원 존재 충돌이 아닌 일시적 처리 불가로 변환한다")
    @Test
    void translatesLockTimeoutToTemporaryUnavailability() {
        UUID identityId = UUID.randomUUID();
        CannotAcquireLockException cause = new CannotAcquireLockException(
                "Lock wait timeout exceeded"
        );
        when(challengeRepository.findByIdentityIdForUpdate(identityId)).thenThrow(cause);

        assertThatThrownBy(() ->
                adapter.findEmailVerificationChallengeByIdentityIdForUpdate(identityId))
                .isInstanceOfSatisfying(
                        IdentityOperationUnavailableException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("낙관적 lock 경쟁은 중립 accepted 응답으로 숨길 수 없는 일시적 처리 불가다")
    @Test
    void translatesOptimisticContentionToTemporaryUnavailability() {
        Account account = mock(Account.class);
        OptimisticLockingFailureException cause = new OptimisticLockingFailureException(
                "stale account version"
        );
        when(accountRepository.saveAndFlush(account)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveAccount(account))
                .isInstanceOfSatisfying(
                        IdentityOperationUnavailableException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("Spring이 transient로 분류한 data access 장애도 재시도 가능한 경계로 유지한다")
    @Test
    void translatesTransientDataAccessFailureToTemporaryUnavailability() {
        Account account = mock(Account.class);
        TransientDataAccessResourceException cause =
                new TransientDataAccessResourceException("temporary database outage");
        when(accountRepository.saveAndFlush(account)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveAccount(account))
                .isInstanceOfSatisfying(
                        IdentityOperationUnavailableException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("가입 첫 신원 조회의 연결 장애도 일반화된 일시적 처리 불가로 변환한다")
    @Test
    void translatesIdentityLookupResourceFailureToTemporaryUnavailability() {
        DataAccessResourceFailureException cause = new DataAccessResourceFailureException(
                "database connection is unavailable"
        );
        when(identityRepository.findByProviderAndProviderSubject(
                IdentityProvider.LOCAL_EMAIL,
                "member@example.com"
        )).thenThrow(cause);

        assertThatThrownBy(() -> adapter.findIdentity(
                IdentityProvider.LOCAL_EMAIL,
                "member@example.com"
        )).isInstanceOfSatisfying(
                IdentityOperationUnavailableException.class,
                exception -> assertThat(exception.getCause()).isSameAs(cause)
        );
    }

    @DisplayName("계정 단순 조회의 복구 가능한 장애도 재시도 가능한 경계로 유지한다")
    @Test
    void translatesRecoverableAccountLookupFailureToTemporaryUnavailability() {
        UUID accountId = UUID.randomUUID();
        RecoverableDataAccessException cause = new RecoverableDataAccessException(
                "database requested a retry"
        );
        when(accountRepository.findById(accountId)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.findAccountById(accountId))
                .isInstanceOfSatisfying(
                        IdentityOperationUnavailableException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("인증 token unique 경쟁은 이메일 존재 충돌로 위장하지 않는다")
    @Test
    void keepsChallengeTokenRaceOutOfIdentityConflict() {
        EmailVerificationChallenge challenge = mock(EmailVerificationChallenge.class);
        DataIntegrityViolationException cause = uniqueViolation(
                "uk_email_verification_challenges_token_hash"
        );
        when(challengeRepository.saveAndFlush(challenge)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveEmailVerificationChallenge(challenge))
                .isInstanceOfSatisfying(
                        IdentityOperationUnavailableException.class,
                        exception -> assertThat(exception.getCause()).isSameAs(cause)
                );
    }

    @DisplayName("알 수 없는 영구 data integrity 장애는 신원 충돌로 옮겨 숨기지 않는다")
    @Test
    void preservesUnknownDataIntegrityViolation() {
        AccountIdentity identity = mock(AccountIdentity.class);
        DataIntegrityViolationException cause = uniqueViolation(
                "uk_unknown_identity_constraint"
        );
        when(identityRepository.saveAndFlush(identity)).thenThrow(cause);

        assertThatThrownBy(() -> adapter.saveIdentity(identity)).isSameAs(cause);
    }

    private DataIntegrityViolationException uniqueViolation(String constraintName) {
        ConstraintViolationException violation = mock(ConstraintViolationException.class);
        when(violation.getConstraintName()).thenReturn(constraintName);
        return new DataIntegrityViolationException("고유 제약 충돌", violation);
    }
}
