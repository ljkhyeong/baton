package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AccountJpaRepository extends JpaRepository<Account, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Account> findForUpdateById(UUID id);

    @Query("select account.sessionVersion from Account account where account.id = :id and account.deactivatedAt is null")
    Optional<Long> findSessionVersionById(UUID id);
}
