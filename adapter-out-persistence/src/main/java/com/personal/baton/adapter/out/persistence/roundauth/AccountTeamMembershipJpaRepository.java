package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.AccountTeamMembership;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountTeamMembershipJpaRepository
        extends JpaRepository<AccountTeamMembership, UUID> {

    Optional<AccountTeamMembership> findByAccountIdAndTeamId(UUID accountId, UUID teamId);

    Optional<AccountTeamMembership> findByMemberId(UUID memberId);
}
