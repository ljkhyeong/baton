package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.LocalCredential;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface LocalCredentialJpaRepository extends JpaRepository<LocalCredential, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LocalCredential> findForUpdateByIdentityId(UUID identityId);
}
