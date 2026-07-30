package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.UserAccount;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountJpaRepository extends JpaRepository<UserAccount, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from UserAccount account where account.id = :accountId")
    Optional<UserAccount> findByIdForUpdate(@Param("accountId") UUID accountId);
}
