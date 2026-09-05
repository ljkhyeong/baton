package com.personal.baton.adapter.out.persistence.roundauth;

import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.identity.Account;
import com.personal.baton.application.identity.error.AccountDeactivatedException;
import com.personal.baton.adapter.out.persistence.identity.AccountJpaRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountTeamMembershipClaimTransaction {

    private final EntityManager entityManager;
    private final AccountJpaRepository accounts;
    private final AccountTeamMembershipJpaRepository membershipRepository;

    public AccountTeamMembershipClaimTransaction(
            EntityManager entityManager,
            AccountTeamMembershipJpaRepository membershipRepository,
            AccountJpaRepository accounts
    ) {
        this.entityManager = entityManager;
        this.accounts = accounts;
        this.membershipRepository = membershipRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccountTeamMembership create(AccountTeamMembership membership) {
        accounts.findForUpdateById(membership.getAccountId()).filter(Account::isActive).orElseThrow(AccountDeactivatedException::new);
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
