package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.AccountTeamMembership;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountTeamMembershipClaimTransaction {

    private final EntityManager entityManager;
    private final AccountTeamMembershipJpaRepository membershipRepository;

    public AccountTeamMembershipClaimTransaction(
            EntityManager entityManager,
            AccountTeamMembershipJpaRepository membershipRepository
    ) {
        this.entityManager = entityManager;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccountTeamMembership create(AccountTeamMembership membership) {
        try {
            entityManager.persist(membership);
            entityManager.flush();
            return membership;
        } catch (RuntimeException exception) {
            throw new MembershipInsertException(exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<AccountTeamMembership> findByAccountAndTeam(
            UUID accountId,
            UUID teamId
    ) {
        return membershipRepository.findByAccountIdAndTeamId(accountId, teamId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<AccountTeamMembership> findByMemberId(UUID memberId) {
        return membershipRepository.findByMemberId(memberId);
    }

    static final class MembershipInsertException extends RuntimeException {

        private final RuntimeException persistenceFailure;

        MembershipInsertException(RuntimeException persistenceFailure) {
            super(persistenceFailure);
            this.persistenceFailure = persistenceFailure;
        }

        RuntimeException persistenceFailure() {
            return persistenceFailure;
        }
    }
}
