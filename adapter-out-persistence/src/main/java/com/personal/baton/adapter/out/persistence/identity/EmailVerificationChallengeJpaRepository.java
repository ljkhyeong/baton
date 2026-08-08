package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.EmailVerificationChallenge;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationChallengeJpaRepository
        extends JpaRepository<EmailVerificationChallenge, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select challenge
            from EmailVerificationChallenge challenge
            where challenge.identityId = :identityId
            """)
    Optional<EmailVerificationChallenge> findByIdentityIdForUpdate(
            @Param("identityId") UUID identityId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select challenge
            from EmailVerificationChallenge challenge
            where challenge.tokenHash = :tokenHash
            """)
    Optional<EmailVerificationChallenge> findByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );
}
