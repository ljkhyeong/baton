package com.personal.baton.adapter.out.persistence.identity;

import com.personal.baton.domain.identity.Account;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountJpaRepository extends JpaRepository<Account, UUID> {
}
