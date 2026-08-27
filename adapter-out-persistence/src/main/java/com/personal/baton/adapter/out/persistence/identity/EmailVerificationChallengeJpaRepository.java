package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.EmailVerificationChallenge;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EmailVerificationChallengeJpaRepository
        extends JpaRepository<EmailVerificationChallenge, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationChallenge> findForUpdateByIdentityId(UUID identityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationChallenge> findForUpdateByTokenHash(String tokenHash);
}
